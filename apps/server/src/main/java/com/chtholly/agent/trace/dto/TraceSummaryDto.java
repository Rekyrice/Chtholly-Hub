package com.chtholly.agent.trace.dto;

import com.chtholly.agent.trace.ExecutionTraceRow;

import java.time.Instant;

public record TraceSummaryDto(
        String correlationId,
        Long userId,
        String sessionId,
        Instant startedAt,
        Instant finishedAt,
        Integer durationMs,
        String status,
        Integer stepsCount,
        Integer inputTokens,
        Integer outputTokens,
        String errorMessage
) {
    public static TraceSummaryDto from(ExecutionTraceRow row) {
        return new TraceSummaryDto(
                row.getCorrelationId(),
                row.getUserId(),
                row.getSessionId(),
                row.getStartedAt(),
                row.getFinishedAt(),
                row.getDurationMs(),
                row.getStatus(),
                row.getStepsCount(),
                row.getInputTokens(),
                row.getOutputTokens(),
                row.getErrorMessage()
        );
    }
}
