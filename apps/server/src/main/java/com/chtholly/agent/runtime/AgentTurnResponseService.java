package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.response.AgentFinalAnswerService;
import io.micrometer.observation.Observation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/** Resolves prepared boundaries and loop terminal outcomes into response-stage work. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentTurnResponseService {

    private final AgentFinalAnswerService finalAnswerService;

    /**
     * Creates the response-stage coordinator.
     *
     * @param finalAnswerService validated answer service
     */
    public AgentTurnResponseService(AgentFinalAnswerService finalAnswerService) {
        this.finalAnswerService = finalAnswerService;
    }

    /**
     * Completes a preparation boundary without entering the reasoning loop.
     *
     * @param prepared prepared boundary
     * @param question user question
     * @param memory conversation memory
     * @param sink client event sink
     * @param trace execution trace
     * @param agentSpan root observation
     */
    public void completeBoundary(
            AgentTurnPreparationService.PreparedTurn prepared,
            String question,
            AgentConversationMemory memory,
            Consumer<AgentEvent> sink,
            AgentExecutionTrace trace,
            Observation agentSpan) {
        AgentTurnPreparationService.Boundary boundary = prepared.boundary();
        finalAnswerService.completeBoundary(
                boundary.reason(),
                boundary.skillId(),
                boundary.detail(),
                question,
                memory,
                sink,
                trace,
                agentSpan,
                prepared.turnBudget());
    }

    /**
     * Completes or classifies the terminal result returned by the reasoning loop.
     *
     * @param result loop terminal result
     * @param prepared prepared turn state
     * @param question user question
     * @param memory conversation memory
     * @param sink client event sink
     * @param trace execution trace
     * @param agentSpan root observation
     */
    public void completeLoopResult(
            AgentLoopResult result,
            AgentTurnPreparationService.PreparedTurn prepared,
            String question,
            AgentConversationMemory memory,
            Consumer<AgentEvent> sink,
            AgentExecutionTrace trace,
            Observation agentSpan) {
        EvidenceSet finalEvidenceSet = prepared.contextSnapshot().evidenceSet()
                .append(result.evidenceSet().items());
        boolean finalEvidenceRequired = prepared.contextSnapshot().evidenceRequired()
                || result.evidenceRequired();
        trace.recordRetrieval(
                prepared.contextSnapshot().retrievalStatuses(), finalEvidenceSet);
        if (result.status() == AgentLoopResult.Status.FINAL_READY) {
            completeFinalAnswer(
                    result,
                    prepared,
                    question,
                    memory,
                    sink,
                    trace,
                    agentSpan,
                    finalEvidenceSet,
                    finalEvidenceRequired);
            return;
        }
        if (result.status() == AgentLoopResult.Status.TURN_TIMEOUT) {
            throw AgentTurnBudget.unavailableForStage(
                    AgentTurnBudget.UnavailableReason.TIMEOUT, "agent_loop");
        }
        if (result.status() == AgentLoopResult.Status.CANCELLED) {
            throw AgentTurnBudget.unavailableForStage(
                    AgentTurnBudget.UnavailableReason.CANCELLED, "agent_loop");
        }
        classifyFailure(result.status(), trace);
    }

    private void completeFinalAnswer(
            AgentLoopResult result,
            AgentTurnPreparationService.PreparedTurn prepared,
            String question,
            AgentConversationMemory memory,
            Consumer<AgentEvent> sink,
            AgentExecutionTrace trace,
            Observation agentSpan,
            EvidenceSet finalEvidenceSet,
            boolean finalEvidenceRequired) {
        long streamLlmMs = finalAnswerService.stream(
                sink,
                question,
                result.transcript(),
                memory,
                trace,
                agentSpan,
                result.finalStepIndex(),
                prepared.contextSnapshot(),
                finalEvidenceSet,
                finalEvidenceRequired,
                prepared.selectedSkill(),
                prepared.turnBudget());
        trace.recordStep(
                result.finalStepIndex(),
                "final_answer",
                result.finalDecisionLlmMs() + streamLlmMs,
                0);
    }

    private void classifyFailure(
            AgentLoopResult.Status status,
            AgentExecutionTrace trace) {
        switch (status) {
            case LLM_TIMEOUT -> {
                trace.markFailure(AgentExecutionTrace.FailureType.LLM_TIMEOUT);
                trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.MODEL_FAILURE);
            }
            case TOOL_INTERRUPTED -> trace.markFailure(
                    AgentExecutionTrace.FailureType.TOOL_FAILED);
            case LLM_ERROR, LLM_INTERRUPTED -> {
                trace.markFailure(AgentExecutionTrace.FailureType.INTERNAL_ERROR);
                trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.MODEL_FAILURE);
            }
            case MAX_STEPS -> trace.markFailure(AgentExecutionTrace.FailureType.INTERNAL_ERROR);
            case TURN_TIMEOUT, CANCELLED, FINAL_READY -> {
                // These terminal states are handled before classification.
            }
        }
    }
}
