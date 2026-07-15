package com.chtholly.agent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
                .doesNotContain("input-secret")
                .contains("[REDACTED]");
        assertThat(toolCall.path("observation_summary").asText())
                .doesNotContain("super-secret-token", "plain-secret")
                .contains("[REDACTED]")
                .hasSizeLessThanOrEqualTo(512);
    }
}
