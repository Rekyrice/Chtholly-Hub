package com.chtholly.agent.trace.dto;

/** Bounded administrator-visible content captured from one execution boundary. */
public record TraceContentDto(
        String text,
        Integer sourceChars,
        String sha256,
        Boolean truncated,
        Boolean credentialRedacted
) {
}
