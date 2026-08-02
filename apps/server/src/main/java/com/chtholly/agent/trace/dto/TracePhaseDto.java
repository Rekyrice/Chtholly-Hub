package com.chtholly.agent.trace.dto;

import java.util.List;

/** Contiguous timeline phase preserving canonical event order. */
public record TracePhaseDto(
        String phase,
        List<TraceEventDto> events
) {
}
