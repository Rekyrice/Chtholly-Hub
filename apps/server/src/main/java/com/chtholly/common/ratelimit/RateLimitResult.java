package com.chtholly.common.ratelimit;

/** Outcome of a single rate-limit check. */
public record RateLimitResult(boolean permitted, int retryAfterSeconds) {

    public static RateLimitResult permit() {
        return new RateLimitResult(true, 0);
    }

    public static RateLimitResult deny(int retryAfterSeconds) {
        return new RateLimitResult(false, Math.max(1, retryAfterSeconds));
    }
}
