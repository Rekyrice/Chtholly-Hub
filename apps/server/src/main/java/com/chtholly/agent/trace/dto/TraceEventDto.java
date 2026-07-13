package com.chtholly.agent.trace.dto;

/** Typed model/tool event exposed by an execution trace detail response. */
public record TraceEventDto(
        Integer sequence,
        Integer stepIndex,
        String type,
        String name,
        Long durationMs,
        Boolean success,
        String inputSummary,
        String observationSummary,
        Integer inputChars,
        Integer outputChars,
        Long firstTokenMs
) {
}
