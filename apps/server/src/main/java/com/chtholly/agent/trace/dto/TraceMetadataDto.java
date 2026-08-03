package com.chtholly.agent.trace.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Typed metadata used by the administrator trace detail page. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceMetadataDto(
        String runMode,
        String failureType,
        String outcomeReason,
        Integer llmCallCount,
        Integer toolCallCount,
        Components components,
        Skill skill,
        Retrieval retrieval,
        Turn turn,
        Memory memory,
        ToolPlan toolPlan,
        List<Step> steps,
        AnswerTiming answerTiming,
        Capture capture,
        Completeness completeness,
        Input input
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Components(
            String prompt,
            String skillSelector,
            String model,
            String retrieval,
            String citationValidator,
            String tools,
            String traceSchema
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Skill(
            String selectionStatus,
            String id,
            String version,
            String validationStatus
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Retrieval(
            String strategy,
            RetrievalStatuses statuses,
            Integer evidenceCount,
            String evidenceSnapshotHash,
            Boolean degraded,
            String citationValidationStatus,
            List<Evidence> evidence
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RetrievalStatuses(
            String semantic,
            String keyword,
            String entity,
            @JsonProperty("current_post") String currentPost
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Evidence(
            String citationId,
            String documentId,
            String source,
            String sourceVersion,
            String sourceHash
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Turn(
            String requestId,
            String turnId,
            String chatSessionId,
            String connectionId,
            Long budgetMs,
            Integer maxSteps,
            String timeoutStage,
            Boolean cancelled,
            String clientDeliveryStatus,
            String clientTerminalType,
            String clientDeliveryCode
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Memory(
            String writeStatus,
            String failureCode
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolPlan(
            String reason,
            List<String> effectiveTools
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(
            Integer stepIndex,
            String action,
            Long llmMs,
            Long toolMs
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnswerTiming(
            Long modelFirstTokenMs,
            Long safeAnswerReadyMs,
            Long firstClientDeltaMs
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Capture(
            String level,
            String policyVersion,
            Integer maxContentFieldChars,
            Integer maxTotalContentChars,
            Integer capturedContentChars,
            Integer truncatedContentFields,
            Integer credentialRedactions
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Completeness(
            Integer eventLimit,
            Integer droppedEvents,
            Integer truncatedToolOutputs,
            Boolean complete
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Input(
            String fingerprint,
            String questionFingerprint,
            String pageContextFingerprint,
            TraceContentDto question,
            TraceContentDto pageContext
    ) {
    }
}
