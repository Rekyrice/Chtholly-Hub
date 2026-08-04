package com.chtholly.agent.response;

import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.runtime.AgentTurnBudget;
import com.chtholly.agent.skill.SkillDefinition;
import com.chtholly.agent.skill.SkillOutputValidator;
import io.micrometer.observation.Observation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Applies the one action repair, citation repair, and Skill validation pipeline.
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentFinalAnswerValidationPipeline {

    private final AgentFinalAnswerRepairService repairService;
    private final SkillOutputValidator skillOutputValidator;

    /**
     * Creates the final-answer validation pipeline.
     *
     * @param repairService bounded model repair boundary
     * @param skillOutputValidator selected-Skill output validator
     */
    public AgentFinalAnswerValidationPipeline(
            AgentFinalAnswerRepairService repairService,
            SkillOutputValidator skillOutputValidator) {
        this.repairService = repairService;
        this.skillOutputValidator = skillOutputValidator;
    }

    /**
     * Repairs and validates a buffered candidate before any client event is emitted.
     *
     * @param request immutable validation inputs
     * @return approved answer or stable response boundary
     */
    public Result validate(Request request) {
        String candidate = request.candidate();
        long repairModelDurationMs = 0;
        if (request.actionEnvelope()) {
            long startedAt = System.currentTimeMillis();
            candidate = repairService.repairActionEnvelope(
                    request.system(),
                    request.userPrompt(),
                    request.trace(),
                    request.agentSpan(),
                    request.stepIndex(),
                    request.turnBudget());
            repairModelDurationMs += System.currentTimeMillis() - startedAt;
        }

        EvidenceSet.ValidationResult evidenceValidation = request.finalEvidenceSet().validate(
                candidate, request.finalEvidenceRequired());
        if (evidenceValidation.status() == EvidenceSet.ValidationStatus.MISSING_CITATION
                && !request.finalEvidenceSet().isEmpty()) {
            long startedAt = System.currentTimeMillis();
            candidate = repairService.repairMissingCitations(
                    candidate,
                    request.finalEvidenceSet(),
                    request.trace(),
                    request.agentSpan(),
                    request.stepIndex(),
                    request.turnBudget());
            repairModelDurationMs += System.currentTimeMillis() - startedAt;
            evidenceValidation = request.finalEvidenceSet().validate(
                    candidate, request.finalEvidenceRequired());
        }
        request.trace().recordCitationValidation(evidenceValidation.status().name());
        classifyEvidence(evidenceValidation.status(), request.trace());
        if (requiresBoundary(request.trace())) {
            return Result.boundary(
                    request.trace().getOutcomeReason(),
                    request.selectedSkill() == null ? "" : request.selectedSkill().id(),
                    evidenceValidation.status().name(),
                    repairModelDurationMs);
        }

        SkillValidation skillValidation = validateSkill(
                request, evidenceValidation.safeAnswer());
        if (requiresBoundary(request.trace())) {
            return Result.boundary(
                    request.trace().getOutcomeReason(),
                    request.selectedSkill() == null ? "" : request.selectedSkill().id(),
                    skillValidation.boundaryDetail(),
                    repairModelDurationMs);
        }
        request.turnBudget().check("safe_answer_validation");
        return Result.approved(skillValidation.answer(), repairModelDurationMs);
    }

    private SkillValidation validateSkill(Request request, String answer) {
        if (request.selectedSkill() == null || skillOutputValidator == null) {
            request.trace().recordSkillValidation("NOT_APPLICABLE");
            return new SkillValidation(answer, "");
        }
        SkillOutputValidator.SkillValidationResult validation = skillOutputValidator.validate(
                request.selectedSkill(),
                answer,
                request.finalEvidenceSet(),
                request.question(),
                request.finalEvidenceRequired());
        request.trace().recordSkillValidation(validation.status().name());
        if (validation.status() == SkillOutputValidator.Status.CITATION_INVALID) {
            request.trace().markFailure(AgentExecutionTrace.FailureType.CITATION_INVALID);
            request.trace().recordOutcomeReason(
                    AgentExecutionTrace.OutcomeReason.INVALID_CITATION);
            return new SkillValidation(
                    validation.output(), String.join(",", validation.errors()));
        }
        if (validation.status() != SkillOutputValidator.Status.VALID
                && validation.status() != SkillOutputValidator.Status.INSUFFICIENT_EVIDENCE
                && request.trace().getFailureType() == AgentExecutionTrace.FailureType.NONE) {
            request.trace().markFailure(
                    AgentExecutionTrace.FailureType.SKILL_VALIDATION_FAILED);
        }
        return new SkillValidation(validation.output(), "");
    }

    private void classifyEvidence(
            EvidenceSet.ValidationStatus status,
            AgentExecutionTrace trace) {
        if (status == EvidenceSet.ValidationStatus.UNKNOWN_CITATION
                || status == EvidenceSet.ValidationStatus.MISSING_CITATION) {
            trace.markFailure(AgentExecutionTrace.FailureType.CITATION_INVALID);
            trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.INVALID_CITATION);
        } else if (status == EvidenceSet.ValidationStatus.NO_EVIDENCE) {
            trace.markFailure(AgentExecutionTrace.FailureType.RETRIEVAL_EMPTY);
            trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.NO_EVIDENCE);
        }
    }

    private boolean requiresBoundary(AgentExecutionTrace trace) {
        return trace.getOutcomeReason() == AgentExecutionTrace.OutcomeReason.INVALID_CITATION
                || trace.getOutcomeReason() == AgentExecutionTrace.OutcomeReason.NO_EVIDENCE;
    }

    private record SkillValidation(String answer, String boundaryDetail) {
    }

    /** Immutable validation inputs for one buffered candidate. */
    public record Request(
            String candidate,
            boolean actionEnvelope,
            String system,
            String userPrompt,
            EvidenceSet finalEvidenceSet,
            boolean finalEvidenceRequired,
            SkillDefinition selectedSkill,
            String question,
            AgentExecutionTrace trace,
            Observation agentSpan,
            int stepIndex,
            AgentTurnBudget turnBudget) {
    }

    /** Immutable approved answer or stable validation boundary. */
    public record Result(
            Status status,
            String answer,
            AgentExecutionTrace.OutcomeReason boundaryReason,
            String skillId,
            String boundaryDetail,
            long repairModelDurationMs) {

        private static Result approved(String answer, long repairModelDurationMs) {
            return new Result(
                    Status.APPROVED,
                    answer,
                    AgentExecutionTrace.OutcomeReason.NONE,
                    "",
                    "",
                    repairModelDurationMs);
        }

        private static Result boundary(
                AgentExecutionTrace.OutcomeReason reason,
                String skillId,
                String detail,
                long repairModelDurationMs) {
            return new Result(
                    Status.BOUNDARY,
                    "",
                    reason,
                    skillId,
                    detail,
                    repairModelDurationMs);
        }
    }

    /** Validation terminal state. */
    public enum Status {
        APPROVED,
        BOUNDARY
    }
}
