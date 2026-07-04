package com.chtholly.agent.trace;

import lombok.Data;

import java.time.Instant;

/** trace_failure_patterns 表行映射。 */
@Data
public class TraceFailurePatternRow {
    private Long id;
    private String patternKey;
    private Integer occurrenceCount;
    private Instant lastSeenAt;
    private String sampleTraceIds;
    private String resolutionHint;
    private Instant createdAt;
}
