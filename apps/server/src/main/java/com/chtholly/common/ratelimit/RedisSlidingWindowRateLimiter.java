package com.chtholly.common.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis fixed-window counter using atomic INCR + EXPIRE (Lua).
 *
 * <p>Window bucket is encoded in the key via {@link RateLimitKeys}; each bucket auto-expires.</p>
 */
@Component
public class RedisSlidingWindowRateLimiter implements RateLimiter {

    /**
     * Returns {allowed, retryAfterSeconds}.
     * allowed: 1 = pass, 0 = reject; retryAfterSeconds = TTL when rejected.
     */
    private static final String LUA = """
            local current = redis.call('INCR', KEYS[1])
            local window = tonumber(ARGV[2])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], window)
            end
            if current > tonumber(ARGV[1]) then
              local ttl = redis.call('TTL', KEYS[1])
              if ttl == nil or ttl < 0 then
                ttl = window
              end
              return {0, ttl}
            end
            return {1, 0}
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List<Long>> script;

    public RedisSlidingWindowRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>();
        this.script.setScriptText(LUA);
        this.script.setResultType((Class<List<Long>>) (Class<?>) List.class);
    }

    @Override
    public RateLimitResult tryAcquire(String redisKey, int maxRequests, int windowSeconds) {
        List<Long> result = redis.execute(script, List.of(redisKey),
                String.valueOf(maxRequests), String.valueOf(windowSeconds));
        if (result == null || result.isEmpty()) {
            return RateLimitResult.permit();
        }
        long allowed = result.getFirst();
        if (allowed == 1L) {
            return RateLimitResult.permit();
        }
        int retryAfter = result.size() > 1 ? result.get(1).intValue() : windowSeconds;
        return RateLimitResult.deny(retryAfter);
    }
}
