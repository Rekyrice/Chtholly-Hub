package com.chtholly.agent.trace.dto;

import java.util.List;

/** One explicitly indexed Think-Act-Observe step and its associated trace events. */
public record TraceStepDto(
        Integer stepIndex,
        String action,
        Long llmDurationMs,
        Long toolDurationMs,
        List<TraceEventDto> events
) {
}
