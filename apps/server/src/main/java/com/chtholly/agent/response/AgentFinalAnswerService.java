package com.chtholly.agent.response;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.context.AgentContextSnapshot;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentMemoryWriteException;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.runtime.AgentTurnBudget;
import com.chtholly.agent.runtime.AgentTurnCompletion;
import com.chtholly.agent.skill.SkillDefinition;
import io.micrometer.observation.Observation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Coordinates buffered generation, validation, boundary handling, and visible completion.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentFinalAnswerService {
    private final AgentFinalCandidateGenerator candidateGenerator;
    private final AgentFinalAnswerValidationPipeline validationPipeline;
    private final AgentBoundaryResponseService boundaryResponseService;
    private final AgentTurnCompletion completion;
    private final AgentFinalAnswerPromptFactory promptFactory;
    /**
     * Creates the final-answer application service.
     *
     * @param candidateGenerator buffered candidate generation boundary
     * @param validationPipeline repair and validation pipeline
     * @param boundaryResponseService safe boundary response service
     * @param completion terminal memory and client-event boundary
     * @param promptFactory source of stable client-safe error messages
     */
    public AgentFinalAnswerService(
            AgentFinalCandidateGenerator candidateGenerator,
            AgentFinalAnswerValidationPipeline validationPipeline,
            AgentBoundaryResponseService boundaryResponseService,
            AgentTurnCompletion completion,
            AgentFinalAnswerPromptFactory promptFactory) {
        this.candidateGenerator = candidateGenerator;
        this.validationPipeline = validationPipeline;
        this.boundaryResponseService = boundaryResponseService;
        this.completion = completion;
        this.promptFactory = promptFactory;
    }
    /**
     * Completes an explicit preparation or validation boundary through the dedicated service.
     *
     * @param reason stable boundary reason
     * @param skillId selected Skill identifier, or blank when no Skill was selected
     * @param detail internal boundary detail
     * @param question user question
     * @param memory conversation memory
     * @param sink client event sink
     * @param trace execution trace
     * @param agentSpan root agent observation
     * @param turnBudget effective turn budget
     */
    public void completeBoundary(
            AgentExecutionTrace.OutcomeReason reason,
            String skillId,
            String detail,
            String question,
            AgentConversationMemory memory,
            Consumer<AgentEvent> sink,
            AgentExecutionTrace trace,
            Observation agentSpan,
            AgentTurnBudget turnBudget) {
        boundaryResponseService.complete(
                reason,
                skillId,
                detail,
                question,
                memory,
                sink,
                trace,
                agentSpan,
                turnBudget);
    }
    /**
     * Delivers one buffered, repaired, and validated final answer.
     *
     * @return total final-answer model time, including action and citation repair calls
     */
    public long stream(
            Consumer<AgentEvent> sink,
            String question,
            List<String> transcript,
            AgentConversationMemory memory,
            AgentExecutionTrace trace,
            Observation agentSpan,
            int stepIndex,
            AgentContextSnapshot contextSnapshot,
            EvidenceSet finalEvidenceSet,
            boolean finalEvidenceRequired,
            SkillDefinition selectedSkill,
            AgentTurnBudget turnBudget) {
        long responseStartedAt = System.currentTimeMillis();
        long modelDurationMs = 0;
        try {
            AgentFinalCandidateGenerator.Result candidate = candidateGenerator.generate(
                    new AgentFinalCandidateGenerator.Request(
                            contextSnapshot,
                            finalEvidenceSet,
                            transcript,
                            trace,
                            agentSpan,
                            stepIndex,
                            turnBudget));
            modelDurationMs = candidate.modelDurationMs();
            if (candidate.status() != AgentFinalCandidateGenerator.Status.SUCCESS) {
                completeGenerationFailure(candidate, sink, trace);
                return modelDurationMs;
            }

            AgentFinalAnswerValidationPipeline.Result validation = validationPipeline.validate(
                    new AgentFinalAnswerValidationPipeline.Request(
                            candidate.candidate(),
                            candidate.actionEnvelope(),
                            candidate.system(),
                            candidate.userPrompt(),
                            finalEvidenceSet,
                            finalEvidenceRequired,
                            selectedSkill,
                            question,
                            trace,
                            agentSpan,
                            stepIndex,
                            turnBudget));
            modelDurationMs += validation.repairModelDurationMs();
            if (validation.status() == AgentFinalAnswerValidationPipeline.Status.BOUNDARY) {
                boundaryResponseService.complete(
                        validation.boundaryReason(),
                        validation.skillId(),
                        validation.boundaryDetail(),
                        question,
                        memory,
                        sink,
                        trace,
                        agentSpan,
                        turnBudget);
                return modelDurationMs;
            }

            completion.completeVisibleAnswer(
                    memory,
                    question,
                    validation.answer(),
                    turnBudget,
                    trace.getTurnControl(),
                    trace,
                    sink,
                    candidate.modelFirstTokenTurnMs(),
                    System.currentTimeMillis() - trace.getStartedAtMs());
            return modelDurationMs;
        } catch (AgentTurnBudget.UnavailableException unavailable) {
            throw unavailable;
        } catch (AgentMemoryWriteException memoryFailure) {
            throw memoryFailure;
        } catch (Exception failure) {
            if (turnBudget.isCancelled() || turnBudget.isExpired()) {
                throw AgentTurnBudget.unavailableForStage(
                        turnBudget.isCancelled()
                                ? AgentTurnBudget.UnavailableReason.CANCELLED
                                : AgentTurnBudget.UnavailableReason.TIMEOUT,
                        "final_answer");
            }
            log.warn("Agent final answer pipeline failed ({})",
                    failure.getClass().getSimpleName());
            trace.terminateError();
            trace.markFailure(failure instanceof AgentInvalidFinalAnswerException
                    ? AgentExecutionTrace.FailureType.LLM_INVALID_OUTPUT
                    : AgentExecutionTrace.FailureType.INTERNAL_ERROR);
            trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.MODEL_FAILURE);
            trace.setErrorMessage(promptFactory.responseFailed());
            completion.emitError(sink, promptFactory.responseFailed());
            return Math.max(modelDurationMs, System.currentTimeMillis() - responseStartedAt);
        }
    }
    private void completeGenerationFailure(
            AgentFinalCandidateGenerator.Result candidate,
            Consumer<AgentEvent> sink,
            AgentExecutionTrace trace) {
        if (candidate.status() == AgentFinalCandidateGenerator.Status.TIMEOUT) {
            trace.terminateTimeout();
            trace.markFailure(AgentExecutionTrace.FailureType.LLM_TIMEOUT);
        } else {
            trace.terminateError();
            trace.markFailure(AgentExecutionTrace.FailureType.INTERNAL_ERROR);
        }
        trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.MODEL_FAILURE);
        trace.setErrorMessage(candidate.clientMessage());
        completion.emitError(sink, candidate.clientMessage());
    }
}
