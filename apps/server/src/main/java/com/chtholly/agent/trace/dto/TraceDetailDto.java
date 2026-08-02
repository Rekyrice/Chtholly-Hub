package com.chtholly.agent.trace.dto;

import com.chtholly.agent.trace.ExecutionTraceRow;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** Typed administrator projection of one persisted execution trace. */
public record TraceDetailDto(
        String correlationId,
        Long userId,
        String sessionId,
        String status,
        Integer durationMs,
        Integer stepsCount,
        String errorMessage,
        Compatibility compatibility,
        TimingAccuracy timingAccuracy,
        List<TracePhaseDto> phases,
        TraceMetadataDto metadata
) {
    /** Projects a database row into the explicit administrator contract. */
    public static TraceDetailDto from(ExecutionTraceRow row, ObjectMapper objectMapper) {
        return TraceDetailProjector.project(row, objectMapper);
    }

    public enum Compatibility {
        NATIVE_V4,
        LEGACY_V3,
        UNSUPPORTED,
        MALFORMED
    }

    public enum TimingAccuracy {
        EXACT,
        DURATION_ONLY,
        NONE
    }
}
