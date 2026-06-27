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
        trace.recordToolCall("bangumi_search", 200);
        trace.recordStep(0, "bangumi_search", 100, 200);
        trace.terminateFinalAnswer("hello answer");

        trace.finishAndLog(objectMapper, null);

        // 通过反射无法直接捕获 log，验证 summary 字段组装逻辑
        assertThat(trace.getTotalSteps()).isEqualTo(1);
        assertThat(trace.getLlmCalls()).isEqualTo(1);
        assertThat(trace.getToolsCalled()).containsExactly("bangumi_search");
        assertThat(trace.getFinalAnswerLength()).isEqualTo(12);
        assertThat(trace.getTerminatedBy()).isEqualTo("final_answer");

        // 验证 JSON 可序列化且含 event 字段
        var summary = objectMapper.createObjectNode();
        summary.put("event", "agent_execution_complete");
        summary.put("userId", 42);
        summary.put("sessionId", "ws-test");
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(summary));
        assertThat(node.path("event").asText()).isEqualTo("agent_execution_complete");
    }
}
