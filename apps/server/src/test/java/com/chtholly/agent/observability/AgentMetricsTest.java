package com.chtholly.agent.observability;

import com.chtholly.common.tracing.CorrelationIdSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void recordTtftAndTpot_registerTimers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.recordTtft(120);
        metrics.recordTpot(800, 40);

        assertThat(registry.find("agent.llm.ttft").timer()).isNotNull();
        assertThat(registry.find("agent.llm.ttft").timer().count()).isEqualTo(1);
        assertThat(registry.find("agent.llm.tpot").timer()).isNotNull();
        assertThat(registry.find("agent.llm.tpot").timer().count()).isEqualTo(1);
    }

    @Test
    void finishAndLog_recordsTtftFromTrace() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        AgentExecutionTrace trace = new AgentExecutionTrace(1L, "ws", 3);
        trace.recordLlmCall(500, 100, 200, 150L);
        trace.terminateFinalAnswer("answer");
        trace.finish();

        trace.finishAndLog(new com.fasterxml.jackson.databind.ObjectMapper(), metrics);

        assertThat(registry.find("agent.llm.ttft").timer().count()).isEqualTo(1);
        assertThat(registry.find("agent.llm.tpot").timer().count()).isEqualTo(1);
    }

    @Test
    void traceUsesMdcCorrelationIdWhenPresent() {
        MDC.put(CorrelationIdSupport.MDC_CORRELATION_ID, "abc-def-123");
        AgentExecutionTrace trace = new AgentExecutionTrace(1L, "ws", 3);
        assertThat(trace.getCorrelationId()).isEqualTo("abcdef123");
    }
}
