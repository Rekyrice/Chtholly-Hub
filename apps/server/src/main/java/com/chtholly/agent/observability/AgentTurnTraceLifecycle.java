package com.chtholly.agent.observability;

import com.chtholly.agent.runtime.AgentSpanAttributes;
import com.chtholly.agent.runtime.AgentTurnBudget;
import com.chtholly.agent.runtime.AgentTurnControl;
import com.chtholly.agent.trace.TracePersistenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Owns creation and transport-aware finalization of one agent execution trace.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentTurnTraceLifecycle {

    private final ObjectMapper objectMapper;
    private final AgentMetrics agentMetrics;
    private final AgentObservationService observationService;
    private final TracePersistenceService tracePersistenceService;

    /**
     * Creates the trace lifecycle boundary.
     *
     * @param objectMapper trace payload mapper
     * @param agentMetrics aggregate agent metrics
     * @param observationService tracing observation adapter
     * @param tracePersistenceService trace persistence adapter
     */
    public AgentTurnTraceLifecycle(
            ObjectMapper objectMapper,
            AgentMetrics agentMetrics,
            AgentObservationService observationService,
            TracePersistenceService tracePersistenceService) {
        this.objectMapper = objectMapper;
        this.agentMetrics = agentMetrics;
        this.observationService = observationService;
        this.tracePersistenceService = tracePersistenceService;
    }

    /**
     * Starts the root trace and observation for one turn.
     *
     * @return trace scope shared by the turn stages
     */
    public TraceScope begin(
            long userId,
            AgentTurnControl control,
            int maxSteps,
            String question,
            String pageContext,
            String model) {
        AgentExecutionTrace trace = new AgentExecutionTrace(userId, control, maxSteps);
        trace.recordTurnContext(question, pageContext, model, "candidate");
        Observation span = observationService.startAgentSpan(trace.getCorrelationId(), userId);
        return new TraceScope(trace, span);
    }

    /**
     * Defers durable trace finalization until the transport records terminal delivery.
     */
    public void finishAfterClientDelivery(TraceScope scope, AgentTurnControl control) {
        scope.trace().recordCancellation(control.isCancelled());
        control.onClientDeliveryResolved(() -> finalizeTrace(scope, control));
    }

    /**
     * Applies stable timeout or cancellation state and returns an optional visible error message.
     */
    public String recordUnavailable(
            AgentTurnBudget.UnavailableException unavailable,
            AgentTurnControl control,
            AgentExecutionTrace trace,
            String timeoutMessage) {
        trace.recordCancellation(control.isCancelled());
        if (unavailable.reason() == AgentTurnBudget.UnavailableReason.TIMEOUT
                && trace.getTimeoutStage().isBlank()) {
            trace.recordTimeoutStage(unavailable.stage());
        }
        if (unavailable.reason() == AgentTurnBudget.UnavailableReason.CANCELLED) {
            trace.terminateCancelled();
            trace.markFailure(AgentExecutionTrace.FailureType.TURN_CANCELLED);
            trace.setErrorMessage("TURN_CANCELLED");
            return null;
        }
        trace.terminateTimeout();
        trace.markFailure(AgentExecutionTrace.FailureType.TURN_TIMEOUT);
        trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.MODEL_FAILURE);
        trace.setErrorMessage(timeoutMessage);
        return timeoutMessage;
    }

    private void finalizeTrace(TraceScope scope, AgentTurnControl control) {
        AgentExecutionTrace trace = scope.trace();
        try {
            trace.recordCancellation(control.isCancelled());
            trace.finish();
        } catch (RuntimeException exception) {
            log.warn("Agent trace finalization failed correlationId={}: {}",
                    trace.getCorrelationId(), exception.getMessage(), exception);
        }
        try {
            trace.resolveClientDelivery();
        } catch (RuntimeException exception) {
            log.warn("Agent trace delivery resolution failed correlationId={}: {}",
                    trace.getCorrelationId(), exception.getMessage(), exception);
        }
        try {
            observationService.finishSpan(
                    scope.agentSpan(), AgentSpanAttributes.agent(trace), Map.of());
        } catch (RuntimeException exception) {
            log.warn("Agent observation finalization failed correlationId={}: {}",
                    trace.getCorrelationId(), exception.getMessage(), exception);
        }
        try {
            trace.finishAndLog(objectMapper, agentMetrics);
        } catch (RuntimeException exception) {
            log.warn("Agent metrics finalization failed correlationId={}: {}",
                    trace.getCorrelationId(), exception.getMessage(), exception);
        }
        try {
            tracePersistenceService.persist(trace);
        } catch (RuntimeException exception) {
            log.warn("Agent trace submission failed correlationId={}: {}",
                    trace.getCorrelationId(), exception.getMessage(), exception);
        }
    }

    /** Root trace and observation shared by all stages of one turn. */
    public record TraceScope(AgentExecutionTrace trace, Observation agentSpan) {
    }
}
