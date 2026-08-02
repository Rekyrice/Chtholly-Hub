package com.chtholly.agent.observability;

import com.chtholly.agent.evidence.Evidence;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.runtime.AgentTurnControl;
import com.chtholly.agent.runtime.AgentToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionTraceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void finishAndLogProducesStructuredSummary() throws Exception {
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, "ws-test", 5);
        trace.recordLlmCall(100, 400, 800);
        trace.recordToolCall("bangumi_search", 200, "{\"keyword\":\"re0\"}", "ok", true);
        trace.recordStep(0, "bangumi_search", 100, 200);
        trace.terminateFinalAnswer("hello answer");
        trace.finish();

        trace.finishAndLog(objectMapper, null);

        assertThat(trace.getCorrelationId()).isNotBlank();
        assertThat(trace.getStatus()).isEqualTo(com.chtholly.agent.trace.TraceStatus.SUCCESS);
        assertThat(trace.getTotalSteps()).isEqualTo(1);
        assertThat(trace.getLlmCalls()).isEqualTo(1);
        assertThat(trace.getToolsCalled()).containsExactly("bangumi_search");
        assertThat(trace.getFinalAnswerLength()).isEqualTo(12);
        assertThat(trace.getTerminatedBy()).isEqualTo("final_answer");
        assertThat(trace.toPayloadMap()).containsEntry("correlationId", trace.getCorrelationId());

        // 验证 JSON 可序列化且含 event 字段
        var summary = objectMapper.createObjectNode();
        summary.put("event", "agent_execution_complete");
        summary.put("userId", 42);
        summary.put("sessionId", "ws-test");
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(summary));
        assertThat(node.path("event").asText()).isEqualTo("agent_execution_complete");
    }

    @Test
    void recordToolCallUsesExplicitFailureStatusInsteadOfObservationText() {
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, "ws-test", 5);

        trace.recordToolCall("failing_tool", 20, "{}", "Tool failed: boom", false);

        JsonNode toolCalls = objectMapper.valueToTree(trace.toPayloadMap().get("toolCalls"));
        assertThat(toolCalls.path(0).path("success").asBoolean()).isFalse();
    }

    @Test
    void recordsStepAssociationSequenceAndSanitizedObservationSummary() {
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, "ws-test", 5);
        String sensitiveObservation = "authorization=Bearer super-secret-token password=plain-secret "
                + "x".repeat(700);

        trace.recordLlmCall(0, 100, 400, 80, null);
        trace.recordToolCall(
                0,
                "fulltext_search",
                200,
                "{\"accessToken\":\"input-secret\",\"query\":\"trace\"}",
                sensitiveObservation,
                true);

        JsonNode payload = objectMapper.valueToTree(trace.toPayloadMap());
        JsonNode llmCall = payload.path("llmCalls").path(0);
        JsonNode toolCall = payload.path("toolCalls").path(0);

        assertThat(llmCall.path("step_index").asInt()).isZero();
        assertThat(llmCall.path("sequence").asInt()).isEqualTo(1);
        assertThat(toolCall.path("step_index").asInt()).isZero();
        assertThat(toolCall.path("sequence").asInt()).isEqualTo(2);
        assertThat(toolCall.path("input_summary").asText())
                .doesNotContain("input-secret", "query", "trace")
                .matches("sha256=[a-f0-9]{64};chars=\\d+");
        assertThat(toolCall.path("observation_summary").asText())
                .doesNotContain("super-secret-token", "plain-secret", "authorization")
                .matches("sha256=[a-f0-9]{64};chars=\\d+");
    }

    @Test
    void tracePayloadCarriesReplayableVersionsEvidenceAndFixedFailureClassification() {
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, "ws-test", 5);
        EvidenceSet evidence = EvidenceSet.of(List.of(new Evidence(
                "ev-1", "POST", "post:1", "post:1", "post:1#0",
                "title", "semantic+keyword", "v1", "hash-1", "excerpt",
                1, 0.9, Set.of("PUBLIC"), "E1")), Set.of("PUBLIC"));

        trace.recordTurnContext(
                "question token=super-secret", "page: /post/1", "deepseek-chat", "candidate");
        trace.recordSkillSelection("SELECTED", "page-explain", "v1");
        trace.recordSkillValidation("VALID");
        trace.recordRetrieval(Map.of(
                "semantic", "SUCCESS_RESULTS",
                "keyword", "SUCCESS_RESULTS",
                "entity", "SUCCESS_EMPTY"), evidence);
        trace.recordCitationValidation("UNKNOWN_CITATION");
        trace.recordTools(Set.of("article_rag"));
        trace.markFailure(AgentExecutionTrace.FailureType.CITATION_INVALID);

        JsonNode payload = objectMapper.valueToTree(trace.toPayloadMap());

        assertThat(payload.path("components").path("prompt").asText()).isEqualTo("agent-prompt-v1");
        assertThat(payload.path("components").path("model").asText()).isEqualTo("deepseek-chat");
        assertThat(payload.path("components").path("retrieval").asText()).isEqualTo("document-rrf-v1");
        assertThat(payload.path("components").path("citationValidator").asText())
                .isEqualTo("evidence-citation-gate-v1");
        assertThat(payload.path("components").path("tools").asText()).isEqualTo("agent-tool-v1");
        assertThat(payload.path("toolVersions").path("article_rag").asText())
                .isEqualTo("agent-tool-v1");
        assertThat(payload.path("skill").path("id").asText()).isEqualTo("page-explain");
        assertThat(payload.path("skill").path("version").asText()).isEqualTo("v1");
        assertThat(payload.path("skill").path("validationStatus").asText()).isEqualTo("VALID");
        assertThat(payload.path("retrieval").path("statuses").path("entity").asText())
                .isEqualTo("SUCCESS_EMPTY");
        assertThat(payload.path("retrieval").path("evidenceCount").asInt()).isEqualTo(1);
        assertThat(payload.path("retrieval").path("citationValidationStatus").asText())
                .isEqualTo("UNKNOWN_CITATION");
        assertThat(payload.path("retrieval").path("evidence").path(0).path("documentId").asText())
                .isEqualTo("post:1");
        assertThat(payload.path("retrieval").path("evidence").path(0).toString())
                .doesNotContain("excerpt", "title");
        assertThat(payload.path("failureType").asText()).isEqualTo("CITATION_INVALID");
        assertThat(payload.path("runMode").asText()).isEqualTo("candidate");
        assertThat(payload.path("input").path("fingerprint").asText()).hasSize(64);
        assertThat(payload.path("input").path("questionFingerprint").asText()).hasSize(64);
        assertThat(payload.path("input").path("pageContextFingerprint").asText()).hasSize(64);
        assertThat(payload.toString())
                .contains("question token", "page: /post/1")
                .doesNotContain("super-secret", "excerpt");
    }

    @Test
    void llmCallCountIsNotOverwrittenByCallEvents() {
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, "ws-test", 5);
        trace.recordLlmCall(0, 100, 200, 80, 12L);
        trace.recordLlmCall(1, 120, 220, 90, 15L);

        JsonNode payload = objectMapper.valueToTree(trace.toPayloadMap());

        assertThat(payload.path("llmCallCount").asInt()).isEqualTo(2);
        assertThat(payload.path("llmCalls").isArray()).isTrue();
        assertThat(payload.path("llmCalls")).hasSize(2);
    }

    @Test
    void payloadCarriesTurnBudgetToolPlanAndSafeAnswerTimingWithoutContent() {
        AgentTurnControl control = AgentTurnControl.create(
                "request-sensitive-id",
                "turn-1234",
                "sess-page-explain",
                "ws-connection-1",
                Duration.ofSeconds(45));
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, control, 5);
        trace.recordTurnContext("private question", "private page", "test-model", "candidate");
        trace.recordToolPlan("skill_evidence_auto", Set.of("bangumi_search"));
        trace.recordAnswerTiming(120L, 800L, 805L);
        trace.recordTimeoutStage("citation_repair");
        control.cancel();
        trace.recordCancellation(control.isCancelled());

        JsonNode payload = objectMapper.valueToTree(trace.toPayloadMap());

        assertThat(trace.getCorrelationId()).isEqualTo("turn1234");
        assertThat(payload.path("turn").path("requestId").asText())
                .isEqualTo("request-sensitive-id");
        assertThat(payload.path("turn").path("turnId").asText()).isEqualTo("turn-1234");
        assertThat(payload.path("turn").path("chatSessionId").asText())
                .isEqualTo("sess-page-explain");
        assertThat(payload.path("turn").path("connectionId").asText())
                .isEqualTo("ws-connection-1");
        assertThat(payload.path("turn").path("budgetMs").asLong()).isEqualTo(45_000L);
        assertThat(payload.path("turn").path("timeoutStage").asText())
                .isEqualTo("citation_repair");
        assertThat(payload.path("turn").path("cancelled").asBoolean()).isTrue();
        assertThat(payload.path("toolPlan").path("reason").asText())
                .isEqualTo("skill_evidence_auto");
        assertThat(payload.path("toolPlan").path("effectiveTools"))
                .containsExactly(objectMapper.valueToTree("bangumi_search"));
        assertThat(payload.path("answerTiming").path("modelFirstTokenMs").asLong())
                .isEqualTo(120L);
        assertThat(payload.path("answerTiming").path("safeAnswerReadyMs").asLong())
                .isEqualTo(800L);
        assertThat(payload.path("answerTiming").path("firstClientDeltaMs").asLong())
                .isEqualTo(805L);
        assertThat(payload.path("components").path("traceSchema").asText())
                .isEqualTo("agent-trace-v4");
        assertThat(payload.toString()).contains("private question", "private page");
    }

    @Test
    void recordsCompleteLlmContractAndUnifiedEventWithoutContent() {
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, "ws-test", 5);

        trace.recordLlmCall(
                2,
                "ANSWER",
                "deepseek-chat",
                "TIMEOUT",
                "LLM_DEADLINE_EXCEEDED",
                3,
                9_000,
                7_500,
                1_500,
                2_048,
                512,
                180L);

        JsonNode payload = objectMapper.valueToTree(trace.toPayloadMap());
        JsonNode call = payload.path("llmCalls").path(0);
        JsonNode event = payload.path("events").path(0);

        assertThat(call.path("purpose").asText()).isEqualTo("ANSWER");
        assertThat(call.path("model").asText()).isEqualTo("deepseek-chat");
        assertThat(call.path("status").asText()).isEqualTo("TIMEOUT");
        assertThat(call.path("error_code").asText()).isEqualTo("LLM_DEADLINE_EXCEEDED");
        assertThat(call.path("attempt").asInt()).isEqualTo(3);
        assertThat(call.path("budget_before_ms").asLong()).isEqualTo(9_000L);
        assertThat(call.path("budget_after_ms").asLong()).isEqualTo(7_500L);
        assertThat(call.path("duration_ms").asLong()).isEqualTo(1_500L);
        assertThat(event.path("sequence").asInt()).isEqualTo(call.path("sequence").asInt());
        assertThat(event.path("phase").asText()).isEqualTo("llm");
        assertThat(event.path("type").asText()).isEqualTo("llm");
        assertThat(event.path("name").asText()).isEqualTo("llm_call");
        assertThat(event.path("status").asText()).isEqualTo("TIMEOUT");
        assertThat(event.path("step_index").asInt()).isEqualTo(2);
        assertThat(event.path("attempt").asInt()).isEqualTo(3);
        assertThat(event.path("started_offset_ms").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(event.path("details").fieldNames()).toIterable()
                .containsExactly("purpose", "model", "input_chars", "output_chars", "first_token_ms");
        assertThat(call.fieldNames()).toIterable().doesNotContain("prompt", "output");
        assertThat(event.path("details").fieldNames()).toIterable().doesNotContain("prompt", "output");
    }

    @Test
    void adminTraceCapturesReplayableTurnLlmToolAndFinalDeliveryContent() {
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, "ws-test", 5);
        trace.recordTurnContext(
                "原始问题 https://example.com/post Authorization=Bearer turn-secret",
                "post:42 页面正文",
                "deepseek-chat",
                "candidate");
        trace.recordLlmCall(
                0,
                "LOOP_DECISION",
                "deepseek-chat",
                "ERROR",
                "LLM_ERROR",
                1,
                9_000,
                8_000,
                1_000,
                20,
                10,
                null,
                new AgentExecutionTrace.LlmExchange(
                        "system prompt Cookie=sid-secret",
                        "user prompt with full history",
                        "raw model output",
                        "java.lang.IllegalStateException",
                        "upstream failed access_token=llm-secret"));
        AgentToolResult result = new AgentToolResult(
                "raw tool output",
                AgentToolResult.Status.SUCCESS,
                "",
                AgentToolDiagnostics.fallback("article_rag", "raw tool output"));
        trace.recordToolCall(
                0,
                "article_rag",
                12,
                8_000,
                7_900,
                result,
                "{\"query\":\"原始问题\",\"_userQuestion\":\"完整内部问题\"}",
                "最终进入 Observe 的内容");
        trace.terminateFinalAnswer("最终交付 answer");

        JsonNode payload = objectMapper.valueToTree(trace.toPayloadMap());
        JsonNode turnContext = payload.path("events").path(0).path("details");
        JsonNode llm = payload.path("events").path(1).path("details");
        JsonNode tool = payload.path("events").path(2).path("details");
        JsonNode terminal = payload.path("events").path(3).path("details");

        assertThat(turnContext.path("question").path("text").asText())
                .contains("原始问题", "https://example.com/post")
                .doesNotContain("turn-secret");
        assertThat(turnContext.path("pageContext").path("text").asText()).isEqualTo("post:42 页面正文");
        assertThat(llm.path("systemPrompt").path("text").asText())
                .contains("system prompt").doesNotContain("sid-secret");
        assertThat(llm.path("userPrompt").path("text").asText()).isEqualTo("user prompt with full history");
        assertThat(llm.path("rawOutput").path("text").asText()).isEqualTo("raw model output");
        assertThat(llm.path("failureClass").path("text").asText())
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(llm.path("failureMessage").path("text").asText())
                .contains("upstream failed").doesNotContain("llm-secret");
        assertThat(tool.path("input").path("text").asText())
                .contains("_userQuestion", "完整内部问题");
        assertThat(tool.path("observation").path("text").asText())
                .isEqualTo("最终进入 Observe 的内容");
        assertThat(terminal.path("answer").path("text").asText()).isEqualTo("最终交付 answer");
        assertThat(payload.path("capture").path("level").asText()).isEqualTo("ADMIN_FULL");
        assertThat(payload.path("capture").path("policyVersion").asText())
                .isEqualTo("trace-admin-full-v1");
        assertThat(payload.path("capture").path("maxPerFieldChars").asInt()).isEqualTo(131_072);
        assertThat(payload.path("capture").path("maxCapturedChars").asInt()).isEqualTo(2_097_152);
        assertThat(payload.path("capture").path("capturedChars").asInt()).isPositive();
        assertThat(payload.path("capture").path("redactions").asInt()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void adminTraceEnforcesPerFieldAndWholeTurnCaptureBudgetsWithExplicitMetadata() {
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, "ws-test", 5);
        String oversized = "x".repeat(200_000);

        for (int index = 0; index < 20; index++) {
            trace.recordLlmCall(
                    index,
                    "LOOP_DECISION",
                    "model",
                    "SUCCESS",
                    "",
                    1,
                    0,
                    0,
                    1,
                    oversized.length() * 3,
                    oversized.length(),
                    null,
                    new AgentExecutionTrace.LlmExchange(oversized, oversized, oversized, "", ""));
        }

        JsonNode payload = objectMapper.valueToTree(trace.toPayloadMap());
        JsonNode capture = payload.path("capture");
        JsonNode firstDetails = payload.path("events").path(0).path("details");

        assertThat(firstDetails.path("systemPrompt").path("text").asText()).hasSize(131_072);
        assertThat(firstDetails.path("systemPrompt").path("sourceChars").asInt()).isEqualTo(200_000);
        assertThat(firstDetails.path("systemPrompt").path("truncated").asBoolean()).isTrue();
        assertThat(capture.path("capturedChars").asLong()).isLessThanOrEqualTo(2_097_152L);
        assertThat(capture.path("truncated").asBoolean()).isTrue();
        assertThat(capture.path("truncatedFields").asInt()).isPositive();
    }

    @Test
    void recordsAllToolStatusesDiagnosticsAndLegacySuccess() {
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, "ws-test", 5);
        int index = 0;
        for (AgentToolResult.Status status : AgentToolResult.Status.values()) {
            AgentToolDiagnostics diagnostics = new AgentToolDiagnostics(
                    "article_search",
                    "mysql",
                    "public_only",
                    Map.of("query", "safe query"),
                    "bounded output",
                    "a".repeat(64),
                    14,
                    status == AgentToolResult.Status.TIMEOUT,
                    2,
                    List.of("post:1", "post:2"),
                    status == AgentToolResult.Status.SUCCESS ? "" : "TOOL_" + status.name(),
                    Map.of(
                            "requestedUrl", "https://example.com/article",
                            "httpStatus", 200,
                            "tokenBudget", 2_048,
                            "requestContext", "c".repeat(600),
                            "authorization", "Bearer trace-secret",
                            "redirectChain", List.of("https://example.com/article")));
            AgentToolResult result = new AgentToolResult(
                    "raw observation must not be copied",
                    status,
                    diagnostics.errorCode(),
                    diagnostics);
            trace.recordToolCall(index++, "article_rag", 50, 4_000, 3_950, result);
        }
        trace.recordToolCall("legacy_tool", 20, "secret=input", "secret=observation", true);

        JsonNode payload = objectMapper.valueToTree(trace.toPayloadMap());
        JsonNode calls = payload.path("toolCalls");
        JsonNode events = payload.path("events");

        assertThat(calls).hasSize(6);
        for (int statusIndex = 0; statusIndex < AgentToolResult.Status.values().length; statusIndex++) {
            AgentToolResult.Status expected = AgentToolResult.Status.values()[statusIndex];
            JsonNode call = calls.path(statusIndex);
            JsonNode event = events.path(statusIndex);
            assertThat(call.path("success").asBoolean())
                    .isEqualTo(expected == AgentToolResult.Status.SUCCESS);
            assertThat(call.path("status").asText()).isEqualTo(expected.name());
            assertThat(call.path("budget_before_ms").asLong()).isEqualTo(4_000L);
            assertThat(call.path("budget_after_ms").asLong()).isEqualTo(3_950L);
            assertThat(event.path("phase").asText()).isEqualTo("tool");
            assertThat(event.path("details").path("operation").asText()).isEqualTo("article_search");
            assertThat(event.path("details").path("provider").asText()).isEqualTo("mysql");
            assertThat(event.path("details").path("sourcePolicy").asText()).isEqualTo("public_only");
            assertThat(event.path("details").path("sanitizedInput").path("query").asText())
                    .isEqualTo("safe query");
            assertThat(event.path("details").path("outputPreview").asText()).isEqualTo("bounded output");
            assertThat(event.path("details").path("outputSha256").asText()).isEqualTo("a".repeat(64));
            assertThat(event.path("details").path("outputChars").asInt()).isEqualTo(14);
            assertThat(event.path("details").path("resultCount").asInt()).isEqualTo(2);
            assertThat(event.path("details").path("selectedIds")).hasSize(2);
            assertThat(event.path("details").path("attributes").path("requestedUrl").asText())
                    .isEqualTo("https://example.com/article");
            assertThat(event.path("details").path("attributes").path("httpStatus").asInt())
                    .isEqualTo(200);
            assertThat(event.path("details").path("attributes").path("tokenBudget").asInt())
                    .isEqualTo(2_048);
            assertThat(event.path("details").path("attributes").path("requestContext").asText())
                    .hasSize(600);
            assertThat(event.path("details").path("attributes").path("authorization").asText())
                    .isEqualTo("[REDACTED]");
        }
        assertThat(calls.path(5).path("success").asBoolean()).isTrue();
        assertThat(calls.path(5).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(events.path(5).path("phase").asText()).isEqualTo("tool");
        assertThat(calls.path(5).path("input_summary").asText()).startsWith("sha256=");
        assertThat(calls.path(5).path("observation_summary").asText()).startsWith("sha256=");
        assertThat(payload.toString()).doesNotContain("raw observation must not be copied", "secret=input");
    }

    @Test
    void toolEventPreservesOrdinarySanitizedInputUrlWhileFilteringCredentials() {
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, "ws-test", 5);
        AgentToolDiagnostics diagnostics = new AgentToolDiagnostics(
                "web_fetch",
                "jdk-http-client",
                "public-web",
                Map.of("url", "https://alice:uri-secret@example.com/article?topic=keep&token=query-secret"),
                "fetched",
                "a".repeat(64),
                7,
                false,
                1,
                List.of(),
                "");

        trace.recordToolCall(
                0,
                "web_fetch",
                10,
                1_000,
                990,
                new AgentToolResult("fetched", AgentToolResult.Status.SUCCESS, "", diagnostics));

        JsonNode sanitizedInput = objectMapper.valueToTree(trace.toPayloadMap())
                .path("events").path(0).path("details").path("sanitizedInput");
        assertThat(sanitizedInput.path("url").asText())
                .isEqualTo("https://[REDACTED]@example.com/article?topic=keep&token=[REDACTED]");
        assertThat(sanitizedInput.toString()).doesNotContain("alice", "uri-secret", "query-secret");
    }

    @Test
    void sharesMonotonicSequenceAndClampsOffsetsDurationsAndBudgets() {
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, "ws-test", 5);
        trace.recordTurnContext("question", "page", "model", "candidate");
        trace.recordLlmCall(-2, "PLAN", "model", "SUCCESS", "", 0, -1, -2, -3, -4, -5, -6L);
        trace.recordToolCall(
                -3,
                "tool",
                -10,
                -20,
                -30,
                new AgentToolResult("ok", AgentToolResult.Status.SUCCESS));
        trace.recordToolCall(-5, "legacy_tool", -11, "input", "observation", false);
        trace.recordStep(
                -4,
                "https://malicious.example/path token=step-secret _conversationHistory=hidden",
                -40,
                -50);

        JsonNode payload = objectMapper.valueToTree(trace.toPayloadMap());
        JsonNode events = payload.path("events");

        assertThat(events).hasSize(4);
        assertThat(events.path(0).path("sequence").asInt()).isEqualTo(1);
        assertThat(events.path(1).path("sequence").asInt()).isEqualTo(2);
        assertThat(events.path(2).path("sequence").asInt()).isEqualTo(3);
        assertThat(events.path(3).path("sequence").asInt()).isEqualTo(4);
        events.forEach(event -> {
            assertThat(event.path("started_offset_ms").asLong()).isGreaterThanOrEqualTo(0L);
            assertThat(event.path("duration_ms").asLong()).isGreaterThanOrEqualTo(0L);
        });
        assertThat(events.path(1).path("attempt").asInt()).isEqualTo(1);
        assertThat(events.path(1).path("budget_before_ms").asLong()).isZero();
        assertThat(events.path(1).path("budget_after_ms").asLong()).isZero();
        assertThat(events.path(2).path("budget_before_ms").asLong()).isZero();
        assertThat(events.path(2).path("budget_after_ms").asLong()).isZero();
        assertThat(payload.path("llmCalls").path(0).path("step_index").asInt()).isZero();
        assertThat(payload.path("toolCalls").path(0).path("step_index").asInt()).isZero();
        assertThat(payload.path("toolCalls").path(1).path("step_index").asInt()).isZero();
        assertThat(events.path(1).path("step_index").asInt()).isZero();
        assertThat(events.path(2).path("step_index").asInt()).isZero();
        assertThat(events.path(3).path("step_index").asInt()).isZero();
        assertThat(payload.path("steps").path(0).path("stepIndex").asInt()).isZero();
        assertThat(payload.path("steps").path(0).path("llmMs").asLong()).isZero();
        assertThat(payload.path("steps").path(0).path("toolMs").asLong()).isZero();
        assertThat(payload.path("steps").path(0).path("action").asText()).isEqualTo("[REDACTED]");
        assertThat(payload.path("steps").toString())
                .doesNotContain("step-secret", "malicious.example", "_conversationHistory");
    }

    @Test
    void lifecycleMethodsEmitSafeOrderedInstantEvents() {
        AgentTurnControl control = AgentTurnControl.create(
                "request-1", "turn-lifecycle", "session-1", "connection-1", Duration.ofSeconds(30));
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, control, 5);
        trace.recordTurnContext("private question", "private page", "model", "candidate");
        trace.recordSkillSelection("SELECTED", "page-explain", "v1");
        trace.recordSkillValidation("VALID");
        trace.recordToolPlan("skill_evidence_auto", Set.of("article_rag"));
        trace.recordRetrieval(Map.of("semantic", "SUCCESS_RESULTS"), EvidenceSet.empty());
        trace.recordCitationValidation("VALID");
        trace.recordMemoryWrite("SUCCESS", "");
        trace.recordAnswerTiming(20L, 30L, 40L);
        trace.markFailure(AgentExecutionTrace.FailureType.NONE);
        control.completeClientDelivery(false, "error", "CLIENT_DELIVERY_FAILED");
        trace.resolveClientDelivery();
        trace.terminateFinalAnswer("private final answer");

        JsonNode events = objectMapper.valueToTree(trace.toPayloadMap()).path("events");

        assertThat(events).extracting(event -> event.path("name").asText()).containsExactly(
                "turn_context",
                "skill_selection",
                "skill_validation",
                "tool_plan",
                "retrieval",
                "citation_validation",
                "memory_write",
                "answer_timing",
                "failure_classification",
                "client_delivery",
                "terminal");
        assertThat(events).allSatisfy(event -> assertThat(event.path("duration_ms").asLong()).isZero());
        assertThat(events).extracting(event -> event.path("phase").asText()).containsOnly(
                "accepted", "skill", "plan", "retrieval", "validation", "memory", "delivery");
        assertThat(objectMapper.valueToTree(trace.toPayloadMap()).toString())
                .contains("private question", "private page", "private final answer");
    }

    @Test
    void privacyMetadataCountsDroppedEventsAndTruncatedToolOutputs() {
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, "ws-test", 5);
        AgentToolDiagnostics diagnostics = AgentToolDiagnostics.fallback("search", "x".repeat(2_000));
        trace.recordToolCall(
                0,
                "search",
                5,
                100,
                95,
                new AgentToolResult("x".repeat(2_000), AgentToolResult.Status.SUCCESS, "", diagnostics));
        for (int index = 0; index < 260; index++) {
            trace.recordSkillValidation("VALID");
        }

        JsonNode payload = objectMapper.valueToTree(trace.toPayloadMap());
        JsonNode privacy = payload.path("privacy");

        assertThat(payload.path("events")).hasSize(256);
        assertThat(privacy.path("captureLevel").asText()).isEqualTo("ADMIN_FULL");
        assertThat(privacy.path("policyVersion").asText()).isEqualTo("trace-admin-full-v1");
        assertThat(privacy.path("contentBounded").asBoolean()).isTrue();
        assertThat(privacy.path("maxInputStringChars").asInt())
                .isEqualTo(AgentTraceSanitizer.MAX_INPUT_STRING_CHARS);
        assertThat(privacy.path("maxOutputPreviewChars").asInt())
                .isEqualTo(AgentTraceSanitizer.MAX_OUTPUT_PREVIEW_CHARS);
        assertThat(privacy.path("maxCollectionItems").asInt())
                .isEqualTo(AgentTraceSanitizer.MAX_COLLECTION_ITEMS);
        assertThat(privacy.path("eventLimit").asInt()).isEqualTo(256);
        assertThat(privacy.path("droppedEvents").asInt()).isEqualTo(5);
        assertThat(privacy.path("truncatedToolOutputs").asInt()).isEqualTo(1);
    }

    @Test
    void eventProjectionBoundsAndRedactsUntrustedMarkers() throws Exception {
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, "ws-test", 5);
        String secret = "never-store-this-secret";
        AgentToolDiagnostics diagnostics = new AgentToolDiagnostics(
                "operation token=" + secret,
                "https://malicious.example/path",
                "_conversationHistory=" + secret,
                Map.of("authorization", "Bearer " + secret, "items", List.of("password=" + secret)),
                "authorization=Bearer " + secret + " https://malicious.example/output",
                "b".repeat(64),
                4_000,
                true,
                1,
                List.of("token=" + secret),
                "error_code=" + secret);
        trace.recordLlmCall(
                0,
                "purpose token=" + secret,
                "https://malicious.example/model",
                "status token=" + secret,
                "error_code=" + secret,
                1,
                10,
                5,
                5,
                10,
                5,
                1L);
        trace.recordToolCall(
                0,
                "https://malicious.example/tool",
                5,
                10,
                5,
                new AgentToolResult("authorization=Bearer " + secret, AgentToolResult.Status.ERROR,
                        "error_code=" + secret, diagnostics));

        String payload = objectMapper.writeValueAsString(trace.toPayloadMap());

        assertThat(payload).doesNotContain(secret, "malicious.example", "Bearer", "_conversationHistory");
        assertThat(payload.length()).isLessThan(20_000);
    }

    @Test
    void legacyOverloadsPopulateV4EventsAndCounters() {
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, "ws-test", 5);
        trace.recordLlmCall(10, 40, 20);
        trace.recordLlmCall(11, 44, 22, 3L);
        trace.recordLlmCall(1, 12, 48, 24, 4L);
        trace.recordToolCall("legacy", 5, "input", "output", true);
        trace.recordToolCall(1, "legacy-step", 6, "input", "output", false);

        JsonNode payload = objectMapper.valueToTree(trace.toPayloadMap());

        assertThat(payload.path("llmCallCount").asInt()).isEqualTo(3);
        assertThat(payload.path("llmCalls")).hasSize(3);
        assertThat(payload.path("toolCalls")).hasSize(2);
        assertThat(payload.path("events")).hasSize(5);
        assertThat(payload.path("llmCalls").path(0).path("purpose").asText()).isEqualTo("LEGACY");
        assertThat(payload.path("llmCalls").path(0).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(payload.path("llmCalls").path(0).path("attempt").asInt()).isEqualTo(1);
        assertThat(trace.getLlmDurationMs()).isEqualTo(33L);
        assertThat(trace.getToolDurationMs()).isEqualTo(11L);
    }

    @Test
    void failedTerminalDeliveryReconcilesAnOtherwiseSuccessfulTrace() {
        AgentTurnControl control = AgentTurnControl.create(
                "request-1", "turn-delivery", "session-1", "connection-1", Duration.ofSeconds(30));
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, control, 5);
        trace.terminateFinalAnswer("answer");
        trace.finish();
        control.completeClientDelivery(false, "final", "CLIENT_DELIVERY_FAILED");

        trace.resolveClientDelivery();
        JsonNode payload = objectMapper.valueToTree(trace.toPayloadMap());

        assertThat(trace.getStatus()).isEqualTo(com.chtholly.agent.trace.TraceStatus.FAILURE);
        assertThat(trace.getFailureType())
                .isEqualTo(AgentExecutionTrace.FailureType.CLIENT_DELIVERY_FAILED);
        assertThat(payload.path("turn").path("clientDeliveryStatus").asText()).isEqualTo("FAILED");
        assertThat(payload.path("turn").path("clientTerminalType").asText()).isEqualTo("final");
    }

    @Test
    void coordinationErrorTerminalReconcilesSuccessfulExecution() {
        AgentTurnControl control = AgentTurnControl.create(
                "request-1", "turn-coordination", "session-1", "connection-1", Duration.ofSeconds(30));
        AgentExecutionTrace trace = new AgentExecutionTrace(42L, control, 5);
        trace.terminateFinalAnswer("answer");
        trace.finish();
        control.completeClientDelivery(true, "error", "TURN_COORDINATION_UNAVAILABLE");

        trace.resolveClientDelivery();

        assertThat(trace.getStatus()).isEqualTo(com.chtholly.agent.trace.TraceStatus.FAILURE);
        assertThat(trace.getFailureType())
                .isEqualTo(AgentExecutionTrace.FailureType.TURN_COORDINATION_FAILED);
    }
}
