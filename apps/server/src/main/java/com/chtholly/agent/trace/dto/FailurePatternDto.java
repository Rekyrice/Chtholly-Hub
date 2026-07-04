package com.chtholly.agent.trace.dto;

import com.chtholly.agent.trace.TraceFailurePatternRow;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record FailurePatternDto(
        String patternKey,
        int occurrenceCount,
        Instant lastSeenAt,
        JsonNode sampleTraceIds,
        String resolutionHint
) {
    public static FailurePatternDto from(TraceFailurePatternRow row, JsonNode sampleTraceIds) {
        return new FailurePatternDto(
                row.getPatternKey(),
                row.getOccurrenceCount() == null ? 0 : row.getOccurrenceCount(),
                row.getLastSeenAt(),
                sampleTraceIds,
                row.getResolutionHint()
        );
    }
}
