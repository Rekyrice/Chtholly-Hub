package com.chtholly.agent.runtime;

import com.chtholly.agent.observability.AgentComponentVersions;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentObservationService;
import io.micrometer.observation.Observation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Starts and classifies the Skill-selection and retrieval spans of turn preparation.
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentPreparationSpanLifecycle {

    private final AgentObservationService observationService;

    /**
     * Creates the preparation span lifecycle.
     *
     * @param observationService observation adapter
     */
    public AgentPreparationSpanLifecycle(AgentObservationService observationService) {
        this.observationService = observationService;
    }

    /**
     * Starts the Skill-selection span beneath the agent turn span.
     *
     * @param agentSpan parent agent span
     * @return started Skill span
     */
    public Observation startSkill(Observation agentSpan) {
        return observationService.startSkillSpan(agentSpan);
    }

    /**
     * Starts the retrieval span beneath the agent turn span.
     *
     * @param agentSpan parent agent span
     * @return started retrieval span
     */
    public Observation startRetrieval(Observation agentSpan) {
        return observationService.startRetrievalSpan(
                agentSpan, AgentComponentVersions.RETRIEVAL);
    }

    /**
     * Finalizes the preparation spans using the completed trace classification.
     *
     * @param retrievalSpan optional retrieval span
     * @param skillSpan optional Skill span
     * @param trace completed or failed turn trace
     */
    public void finish(
            Observation retrievalSpan,
            Observation skillSpan,
            AgentExecutionTrace trace) {
        finishRetrievalSpan(retrievalSpan, trace);
        finishSkillSpan(skillSpan, trace);
    }

    private void finishSkillSpan(Observation span, AgentExecutionTrace trace) {
        if (span == null) {
            return;
        }
        AgentExecutionTrace.FailureType failure = trace.getFailureType();
        boolean skillFailure = failure == AgentExecutionTrace.FailureType.SKILL_NO_MATCH
                || failure == AgentExecutionTrace.FailureType.SKILL_VALIDATION_FAILED
                || (failure == AgentExecutionTrace.FailureType.INTERNAL_ERROR
                && "ERROR".equals(trace.getSkillSelectionStatus()));
        Map<String, String> low = new LinkedHashMap<>();
        low.put("component.version", AgentComponentVersions.SKILL_SELECTOR);
        low.put("status", skillFailure ? "error" : skillSpanStatus(trace));
        if (!trace.getSkillId().isBlank()) {
            low.put("skill.id", trace.getSkillId());
        }
        if (!trace.getSkillVersion().isBlank()) {
            low.put("skill.version", trace.getSkillVersion());
        }
        if (skillFailure) {
            low.put("error.type", failure.name());
        }
        Map<String, String> high = Map.of(
                "skill.selection.status", trace.getSkillSelectionStatus(),
                "skill.validation.status", trace.getSkillValidationStatus());
        if (skillFailure) {
            observationService.finishSpanError(span, "skill_failed", low, high);
        } else {
            observationService.finishSpan(span, low, high);
        }
    }

    private String skillSpanStatus(AgentExecutionTrace trace) {
        if (!"NOT_RUN".equals(trace.getSkillValidationStatus())) {
            return trace.getSkillValidationStatus().toLowerCase(Locale.ROOT);
        }
        return trace.getSkillSelectionStatus().toLowerCase(Locale.ROOT);
    }

    private void finishRetrievalSpan(Observation span, AgentExecutionTrace trace) {
        if (span == null) {
            return;
        }
        AgentExecutionTrace.FailureType failure = trace.getFailureType();
        boolean retrievalFailure = failure == AgentExecutionTrace.FailureType.RETRIEVAL_EMPTY
                || failure == AgentExecutionTrace.FailureType.RETRIEVAL_TIMEOUT
                || failure == AgentExecutionTrace.FailureType.CITATION_INVALID;
        boolean degraded = trace.getRetrievalStatuses().containsValue("FAILED")
                || trace.getRetrievalStatuses().containsValue("TIMEOUT");
        Map<String, String> low = new LinkedHashMap<>();
        low.put("component.version", AgentComponentVersions.RETRIEVAL);
        low.put("status", retrievalFailure ? "error"
                : degraded ? "degraded"
                : trace.getEvidenceCount() == 0 ? "empty" : "success");
        if (retrievalFailure) {
            low.put("error.type", failure.name());
        }
        Map<String, String> high = new LinkedHashMap<>();
        trace.getRetrievalStatuses().forEach((route, status) ->
                high.put("retrieval." + route + ".status", status));
        high.put("retrieval.evidence_count", String.valueOf(trace.getEvidenceCount()));
        high.put("retrieval.degraded", String.valueOf(degraded));
        high.put("retrieval.citation_validation", trace.getCitationValidationStatus());
        if (retrievalFailure) {
            observationService.finishSpanError(span, "retrieval_failed", low, high);
        } else {
            observationService.finishSpan(span, low, high);
        }
    }
}
