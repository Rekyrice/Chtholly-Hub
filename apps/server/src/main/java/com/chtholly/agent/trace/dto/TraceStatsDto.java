package com.chtholly.agent.trace.dto;

import java.time.LocalDate;
import java.util.List;

public record TraceStatsDto(
        int days,
        long totalExecutions,
        long successCount,
        long failureCount,
        long timeoutCount,
        long abortedCount,
        double successRate,
        Double avgDurationMs,
        Integer p95DurationMs,
        List<FailurePatternDto> topFailurePatterns,
        List<TokenTrendPoint> tokenTrend,
        List<ExecutionTrendPoint> executionTrend
) {
    public record TokenTrendPoint(LocalDate day, long inputTokens, long outputTokens) {}

    public record ExecutionTrendPoint(LocalDate day, long totalExecutions, long successCount, double successRate) {}
}
