package com.chtholly.agent.trace;

import lombok.Data;

import java.time.Instant;

/** execution_traces 表行映射。 */
@Data
public class ExecutionTraceRow {
    private Long id;
    private String correlationId;
    private Long userId;
    private String sessionId;
    private Instant startedAt;
    private Instant finishedAt;
    private Integer durationMs;
    private String status;
    private Integer stepsCount;
    private String toolCalls;
    private String errorMessage;
    private Integer inputTokens;
    private Integer outputTokens;
    private String tracePayload;
    private Boolean patternAnalyzed;
    private Instant createdAt;
}
