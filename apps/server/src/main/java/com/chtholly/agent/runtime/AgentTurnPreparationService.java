package com.chtholly.agent.runtime;

import com.chtholly.agent.context.AgentContextSnapshot;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.skill.SkillDefinition;
import io.micrometer.observation.Observation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Orchestrates planning, memory, retrieval, and span ownership for one agent turn.
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentTurnPreparationService {

    private final AgentTurnPlanningService planningService;
    private final AgentContextPreparationService contextPreparationService;
    private final AgentPreparationSpanLifecycle spanLifecycle;

    /**
     * Creates the turn preparation application service.
     *
     * @param planningService Skill and tool planning boundary
     * @param contextPreparationService memory and retrieval boundary
     * @param spanLifecycle preparation span lifecycle
     */
    public AgentTurnPreparationService(
            AgentTurnPlanningService planningService,
            AgentContextPreparationService contextPreparationService,
            AgentPreparationSpanLifecycle spanLifecycle) {
        this.planningService = planningService;
        this.contextPreparationService = contextPreparationService;
        this.spanLifecycle = spanLifecycle;
    }

    /**
     * Prepares either a loop-ready immutable request or an explicit response boundary.
     *
     * @param request turn inputs and shared runtime state
     * @return prepared turn
     */
    public PreparedTurn prepare(Request request) {
        Observation skillSpan = null;
        Observation retrievalSpan = null;
        try {
            request.turnBudget().check("turn_start");
            skillSpan = spanLifecycle.startSkill(request.agentSpan());
            AgentTurnPlanningService.Plan plan = planningService.plan(planningRequest(request));
            if (!plan.ready()) {
                return boundary(
                        AgentExecutionTrace.OutcomeReason.NEEDS_CLARIFICATION,
                        plan.boundary().skillId(),
                        plan.boundary().detail(),
                        plan.turnBudget(),
                        skillSpan,
                        null);
            }

            String historyBlock = contextPreparationService.readHistory(
                    request.memory(), plan.turnBudget());
            retrievalSpan = spanLifecycle.startRetrieval(request.agentSpan());
            AgentContextSnapshot contextSnapshot = contextPreparationService.buildSnapshot(
                    new AgentContextPreparationService.Request(
                            request.question(),
                            request.userId(),
                            request.sessionId(),
                            request.pageContext(),
                            plan.tools(),
                            historyBlock,
                            plan.selection(),
                            plan.taskPlan(),
                            plan.turnBudget()));
            if (contextSnapshot.evidenceRequired()
                    && contextSnapshot.evidenceSet().isEmpty()
                    && !plan.tools().containsKey("web_fetch")) {
                recordMissingEvidence(request.trace(), contextSnapshot, plan.selectedSkill() != null);
                return boundary(
                        AgentExecutionTrace.OutcomeReason.NO_EVIDENCE,
                        plan.selectedSkill() == null ? "" : plan.selectedSkill().id(),
                        "retrieval_empty",
                        plan.turnBudget(),
                        skillSpan,
                        retrievalSpan);
            }

            AgentLoopRequest loopRequest = new AgentLoopRequest(
                    contextSnapshot.systemPrompt(),
                    request.question().trim(),
                    request.userId(),
                    historyBlock,
                    plan.tools(),
                    plan.maxSteps(),
                    plan.turnBudget(),
                    contextSnapshot.evidenceSet(),
                    contextSnapshot.evidenceRequired());
            return new PreparedTurn(
                    Status.READY,
                    loopRequest,
                    contextSnapshot,
                    plan.selectedSkill(),
                    plan.turnBudget(),
                    null,
                    skillSpan,
                    retrievalSpan);
        } catch (RuntimeException exception) {
            spanLifecycle.finish(retrievalSpan, skillSpan, request.trace());
            throw exception;
        }
    }

    /**
     * Finishes preparation spans after downstream validation classifies the turn.
     *
     * @param prepared preparation result carrying optional spans
     * @param trace completed or failed trace
     */
    public void finish(PreparedTurn prepared, AgentExecutionTrace trace) {
        if (prepared != null) {
            spanLifecycle.finish(prepared.retrievalSpan(), prepared.skillSpan(), trace);
        }
    }

    private AgentTurnPlanningService.Request planningRequest(Request request) {
        return new AgentTurnPlanningService.Request(
                request.question(),
                request.userId(),
                request.sessionId(),
                request.pageContext(),
                request.taskType(),
                request.maxSteps(),
                request.trace(),
                request.turnBudget());
    }

    private void recordMissingEvidence(
            AgentExecutionTrace trace,
            AgentContextSnapshot contextSnapshot,
            boolean selectedSkill) {
        trace.recordRetrieval(
                contextSnapshot.retrievalStatuses(), contextSnapshot.evidenceSet());
        trace.recordCitationValidation(EvidenceSet.ValidationStatus.NO_EVIDENCE.name());
        trace.recordSkillValidation(selectedSkill ? "INSUFFICIENT_EVIDENCE" : "NOT_APPLICABLE");
        trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.NO_EVIDENCE);
        trace.markFailure(
                contextSnapshot.retrievalStatuses().containsValue("TIMEOUT")
                        ? AgentExecutionTrace.FailureType.RETRIEVAL_TIMEOUT
                        : AgentExecutionTrace.FailureType.RETRIEVAL_EMPTY);
    }

    private PreparedTurn boundary(
            AgentExecutionTrace.OutcomeReason reason,
            String skillId,
            String detail,
            AgentTurnBudget budget,
            Observation skillSpan,
            Observation retrievalSpan) {
        return new PreparedTurn(
                Status.BOUNDARY,
                null,
                null,
                null,
                budget,
                new Boundary(reason, skillId, detail),
                skillSpan,
                retrievalSpan);
    }

    /** Outcome of turn preparation. */
    public enum Status {
        READY,
        BOUNDARY
    }

    /** Immutable input required to prepare one turn. */
    public record Request(
            String question,
            long userId,
            AgentConversationMemory memory,
            String sessionId,
            String pageContext,
            String taskType,
            int maxSteps,
            AgentExecutionTrace trace,
            Observation agentSpan,
            AgentTurnBudget turnBudget) {
    }

    /** Stable reason for completing a turn without entering the reasoning loop. */
    public record Boundary(
            AgentExecutionTrace.OutcomeReason reason,
            String skillId,
            String detail) {
    }

    /** Immutable preparation result consumed by the turn orchestrator. */
    public record PreparedTurn(
            Status status,
            AgentLoopRequest loopRequest,
            AgentContextSnapshot contextSnapshot,
            SkillDefinition selectedSkill,
            AgentTurnBudget turnBudget,
            Boundary boundary,
            Observation skillSpan,
            Observation retrievalSpan) {
    }
}
