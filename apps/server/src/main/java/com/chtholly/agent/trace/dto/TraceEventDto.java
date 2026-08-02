package com.chtholly.agent.trace.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** One typed event in the administrator execution-trace timeline. */
public record TraceEventDto(
        String id,
        Integer sequence,
        Integer stepIndex,
        String phase,
        String type,
        String name,
        String status,
        Long startedOffsetMs,
        Long durationMs,
        Integer attempt,
        Long budgetBeforeMs,
        Long budgetAfterMs,
        String errorCode,
        Details details
) {
    /** Marker implemented only by fixed, allowlisted event detail records. */
    public sealed interface Details permits LlmDetails, ToolDetails, LifecycleDetails {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LlmDetails(
            String purpose,
            String model,
            Integer inputChars,
            Integer outputChars,
            Long firstTokenMs,
            TraceContentDto systemPrompt,
            TraceContentDto userPrompt,
            TraceContentDto rawOutput,
            String failureClass,
            TraceContentDto failureMessage
    ) implements Details {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolDetails(
            String operation,
            String provider,
            String sourcePolicy,
            Map<String, Object> sanitizedInput,
            String outputPreview,
            String outputSha256,
            Integer outputChars,
            Boolean outputTruncated,
            Integer resultCount,
            List<String> selectedIds,
            String inputSummary,
            String observationSummary,
            TraceContentDto rawInput,
            TraceContentDto rawObservation,
            Map<String, Object> attributes
    ) implements Details {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LifecycleDetails(
            String model,
            String runMode,
            String skillId,
            String skillVersion,
            Integer sourceCount,
            Integer evidenceCount,
            String reason,
            Integer toolCount,
            Long budgetMs,
            String stage,
            String terminalType,
            String deliveryCode,
            Long modelFirstTokenMs,
            Long safeAnswerReadyMs,
            Long firstClientDeltaMs,
            Integer answerChars,
            TraceContentDto finalAnswer
    ) implements Details {
    }
}
