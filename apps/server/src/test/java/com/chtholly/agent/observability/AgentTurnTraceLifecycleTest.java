package com.chtholly.agent.observability;

import com.chtholly.agent.runtime.AgentTurnBudget;
import com.chtholly.agent.runtime.AgentTurnControl;
import com.chtholly.agent.trace.TracePersistenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTurnTraceLifecycleTest {

    private final AgentMetrics metrics = mock(AgentMetrics.class);
    private final AgentObservationService observations = mock(AgentObservationService.class);
    private final TracePersistenceService persistence = mock(TracePersistenceService.class);
    private final Observation span = mock(Observation.class);
    private final AgentTurnTraceLifecycle lifecycle = new AgentTurnTraceLifecycle(
            new ObjectMapper(), metrics, observations, persistence);

    @Test
    void defersTraceFinalizationUntilWebSocketTerminalDeliveryResolves() {
        AgentTurnControl control = AgentTurnControl.create(
                "request", "turn", "session", "connection", Duration.ofSeconds(1));
        when(observations.startAgentSpan(org.mockito.ArgumentMatchers.anyString(), eq(7L)))
                .thenReturn(span);
        AgentTurnTraceLifecycle.TraceScope scope = lifecycle.begin(
                7L, control, 4, "question", "page", "model");

        lifecycle.finishAfterClientDelivery(scope, control);

        verify(persistence, never()).persist(scope.trace());
        control.completeClientDelivery(true, "final", "");
        verify(observations).finishSpan(eq(span), anyMap(), eq(java.util.Map.of()));
        verify(persistence).persist(scope.trace());
    }

    @Test
    void recordsCancellationWithoutTurningItIntoAVisibleTimeout() {
        AgentTurnControl control = AgentTurnControl.standalone("session", Duration.ofSeconds(1));
        control.cancel();
        AgentExecutionTrace trace = new AgentExecutionTrace(7L, control, 4);
        AgentTurnBudget.UnavailableException unavailable = AgentTurnBudget.unavailableForStage(
                AgentTurnBudget.UnavailableReason.CANCELLED,
                "retrieval");

        String visibleError = lifecycle.recordUnavailable(
                unavailable, control, trace, "response timeout");

        assertThat(visibleError).isNull();
        assertThat(trace.getFailureType()).isEqualTo(AgentExecutionTrace.FailureType.TURN_CANCELLED);
    }

    @Test
    void reconciliationFailureDoesNotPreventIndependentTraceFinalizers() {
        AgentTurnControl control = AgentTurnControl.standalone("session", Duration.ofSeconds(1));
        AgentExecutionTrace trace = spy(new AgentExecutionTrace(7L, control, 4));
        trace.terminateError();
        doThrow(new IllegalStateException("delivery reconciliation failed"))
                .when(trace).resolveClientDelivery();
        AgentTurnTraceLifecycle.TraceScope scope =
                new AgentTurnTraceLifecycle.TraceScope(trace, span);

        lifecycle.finishAfterClientDelivery(scope, control);

        verify(observations).finishSpan(eq(span), anyMap(), eq(java.util.Map.of()));
        verify(metrics).recordExecution(anyLong(), anyInt(), anyCollection(), anyString());
        verify(persistence).persist(trace);
    }
}
