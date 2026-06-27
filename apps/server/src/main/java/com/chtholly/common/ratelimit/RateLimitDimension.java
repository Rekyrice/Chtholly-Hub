package com.chtholly.common.ratelimit;

/** Rate limit key dimension: who the quota applies to. */
public enum RateLimitDimension {
    /** Client IP (anonymous or pre-auth endpoints). */
    IP,
    /** Authenticated user ID from JWT. */
    USER,
    /** Request body field (e.g. login identifier). */
    IDENTIFIER
}
