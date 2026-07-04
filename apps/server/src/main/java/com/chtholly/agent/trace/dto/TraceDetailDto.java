package com.chtholly.agent.trace.dto;

import com.chtholly.agent.trace.ExecutionTraceRow;
import com.fasterxml.jackson.databind.JsonNode;

public record TraceDetailDto(
        String correlationId,
        Long userId,
        String sessionId,
        String status,
        Integer durationMs,
        Integer stepsCount,
        String errorMessage,
        JsonNode toolCalls,
        JsonNode tracePayload
) {
    public static TraceDetailDto from(ExecutionTraceRow row, JsonNode toolCalls, JsonNode tracePayload) {
        return new TraceDetailDto(
                row.getCorrelationId(),
                row.getUserId(),
                row.getSessionId(),
                row.getStatus(),
                row.getDurationMs(),
                row.getStepsCount(),
                row.getErrorMessage(),
                toolCalls,
                tracePayload
        );
    }
}
