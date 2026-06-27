package com.chtholly.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies a Redis-backed rate limit to a controller method.
 *
 * <p>Use {@link RateLimits} or repeat this annotation for multiple windows (e.g. per-minute + per-hour).</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(RateLimits.class)
public @interface RateLimit {

    /** Logical limit name (part of Redis key). */
    String key();

    /** Max requests allowed within the window. */
    int maxRequests() default 10;

    /** Window size in seconds. */
    int windowSeconds() default 60;

    /** Dimension used to isolate counters. */
    RateLimitDimension dimension() default RateLimitDimension.IP;

    /**
     * Request body field for {@link RateLimitDimension#IDENTIFIER} (JavaBean/record accessor name).
     * Ignored for IP and USER dimensions.
     */
    String identifierParam() default "";
}
