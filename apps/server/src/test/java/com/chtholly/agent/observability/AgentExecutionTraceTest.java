package com.chtholly.agent.observability;

import com.chtholly.agent.evidence.Evidence;
import com.chtholly.agent.evidence.EvidenceSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
                .doesNotContain("super-secret", "question token", "page: /post/1", "excerpt");
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
}
