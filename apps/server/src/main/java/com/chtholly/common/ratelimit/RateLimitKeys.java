package com.chtholly.common.ratelimit;

import java.time.Instant;

/** Builds Redis keys for fixed-window sliding counters. */
public final class RateLimitKeys {

    private static final String PREFIX = "ratelimit";

    private RateLimitKeys() {
    }

    /**
     * Builds key: {@code ratelimit:{dimension}:{identifier}:{limitKey}:{windowSlot}}.
     *
     * @param dimension     IP / USER / IDENTIFIER
     * @param identifier    resolved client id (IP, userId, phone, etc.)
     * @param limitKey      logical limit name (e.g. auth:login)
     * @param windowSeconds window size in seconds
     */
    public static String build(RateLimitDimension dimension, String identifier, String limitKey, int windowSeconds) {
        long slot = Instant.now().getEpochSecond() / windowSeconds;
        return PREFIX + ":"
                + dimension.name().toLowerCase() + ":"
                + sanitize(identifier) + ":"
                + sanitize(limitKey) + ":"
                + slot;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace(':', '_').replace(' ', '_');
    }
}
