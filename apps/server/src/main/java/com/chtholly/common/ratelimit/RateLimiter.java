package com.chtholly.common.ratelimit;

/**
 * Sliding-window rate limiter backed by Redis.
 *
 * <p>Implementations must use atomic INCR + EXPIRE per window bucket.</p>
 */
public interface RateLimiter {

    /**
     * Attempts to consume one request slot.
     *
     * @param redisKey       full Redis key (includes window slot)
     * @param maxRequests    max allowed requests in the window
     * @param windowSeconds  window length in seconds (used for EXPIRE)
     * @return allowed or rejected with suggested retry delay
     */
    RateLimitResult tryAcquire(String redisKey, int maxRequests, int windowSeconds);
}
