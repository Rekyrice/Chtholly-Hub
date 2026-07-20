package com.chtholly.agent.runtime;

import com.chtholly.agent.observability.AgentExecutionTrace;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSpanAttributesTest {

    @Test
    void spanAttributesDoNotRepeatNativeDurationOrPayloadMeasurements() {
        AgentExecutionTrace trace = new AgentExecutionTrace(7L, "session", 3);
        trace.recordLlmCall(100, 400, 80);
        trace.terminateFinalAnswer("answer");
        trace.finish();

        assertThat(AgentSpanAttributes.agent(trace)).containsOnlyKeys("status");
        assertThat(AgentSpanAttributes.llm("ok")).containsOnlyKeys("status");
        assertThat(AgentSpanAttributes.tool(AgentToolResult.Status.SUCCESS)).containsOnlyKeys("status");
    }
}
