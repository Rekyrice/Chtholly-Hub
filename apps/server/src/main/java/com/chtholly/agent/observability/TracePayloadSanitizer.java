package com.chtholly.agent.observability;

import java.util.regex.Pattern;

/** Sanitizes bounded trace summaries before they are persisted or returned to operators. */
public final class TracePayloadSanitizer {

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+\\-/]+=*");
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\\\"(?:authorization|access[_-]?token|refresh[_-]?token|token|password|secret|api[_-]?key)"
                    + "\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")");
    private static final Pattern TEXT_SECRET = Pattern.compile(
            "(?i)(\\b(?:authorization|access[_-]?token|refresh[_-]?token|token|password|secret|api[_-]?key)"
                    + "\\b\\s*[:=]\\s*)([^\\s,;]+)");

    private TracePayloadSanitizer() {
    }

    /**
     * Masks common credentials and truncates the resulting summary to a stable maximum length.
     *
     * @param value raw trace text
     * @param maxLength maximum retained characters
     * @return sanitized bounded text, never {@code null}
     */
    public static String sanitizeAndTruncate(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        String sanitized = BEARER_TOKEN.matcher(value).replaceAll(REDACTED);
        sanitized = JSON_SECRET.matcher(sanitized).replaceAll("$1" + REDACTED + "$3");
        sanitized = TEXT_SECRET.matcher(sanitized).replaceAll("$1" + REDACTED);
        if (sanitized.length() <= maxLength) {
            return sanitized;
        }
        return sanitized.substring(0, maxLength);
    }
}
