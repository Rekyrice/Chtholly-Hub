package com.chtholly.agent.runtime;

import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentObservationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDecisionGatewayTest {

    @Test
    void retriesOneTransientFailureAndRecordsBothDecisionAttempts() throws Exception {
        AgentLlmInvoker invoker = mock(AgentLlmInvoker.class);
        when(invoker.modelName()).thenReturn("test-model");
        when(invoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenThrow(new ResourceAccessException("connection reset"))
                .thenReturn("{\"action\":\"final\"}");
        AgentObservationService observations = mock(AgentObservationService.class);
        Observation agentSpan = mock(Observation.class);
        Observation failedAttempt = mock(Observation.class);
        Observation successfulAttempt = mock(Observation.class);
        when(observations.startLlmSpan(agentSpan, "test-model"))
                .thenReturn(failedAttempt, successfulAttempt);
        AgentDecisionGateway gateway = new AgentDecisionGateway(invoker, observations);
        AgentExecutionTrace trace = new AgentExecutionTrace(7L, "session", 2);

        String output = gateway.decide("system", "user", 10, 0, trace, null, agentSpan);

        assertThat(output).isEqualTo("{\"action\":\"final\"}");
        verify(invoker, times(2)).call(anyString(), anyString(), anyDouble(), anyInt());
        JsonNode calls = new ObjectMapper().valueToTree(trace.toPayloadMap().get("llmCalls"));
        assertThat(calls).hasSize(2);
        assertThat(calls.path(0).path("error_code").asText())
                .isEqualTo("LLM_TRANSIENT_ERROR");
        assertThat(calls.path(1).path("status").asText()).isEqualTo("SUCCESS");
        verify(observations, times(2)).startLlmSpan(agentSpan, "test-model");
        verify(observations).finishSpanError(
                eq(failedAttempt), eq("llm_transient_error"), anyMap(), anyMap());
        verify(observations).finishSpan(eq(successfulAttempt), anyMap(), anyMap());
    }

    @Test
    void leavesPostInvocationDeadlineAdmissionToTheCallingLoopStage() throws Exception {
        AgentLlmInvoker invoker = mock(AgentLlmInvoker.class);
        when(invoker.modelName()).thenReturn("test-model");
        when(invoker.timeoutSeconds()).thenReturn(3);
        AtomicLong clock = new AtomicLong();
        AgentTurnBudget budget = AgentTurnBudget.start(
                Duration.ofMillis(5), () -> false, clock::get);
        when(invoker.call(
                anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    clock.set(Duration.ofMillis(6).toNanos());
                    return "{\"action\":\"final\"}";
                });
        AgentObservationService observations = mock(AgentObservationService.class);
        Observation agentSpan = mock(Observation.class);
        Observation attemptSpan = mock(Observation.class);
        when(observations.startLlmSpan(agentSpan, "test-model")).thenReturn(attemptSpan);
        AgentDecisionGateway gateway = new AgentDecisionGateway(invoker, observations);
        AgentExecutionTrace trace = new AgentExecutionTrace(7L, "session", 2);

        String output = gateway.decide(
                "system", "user", 10, 0, trace, budget, agentSpan);

        assertThat(output).isEqualTo("{\"action\":\"final\"}");
        JsonNode calls = new ObjectMapper().valueToTree(trace.toPayloadMap().get("llmCalls"));
        assertThat(calls).hasSize(1);
        assertThat(calls.path(0).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(calls.path(0).path("error_code").asText()).isEmpty();
        verify(observations).finishSpan(eq(attemptSpan), anyMap(), anyMap());
    }
}
