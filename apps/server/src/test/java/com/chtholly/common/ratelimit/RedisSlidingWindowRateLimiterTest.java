package com.chtholly.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisSlidingWindowRateLimiterTest {

    @Mock
    private StringRedisTemplate redis;

    private RedisSlidingWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RedisSlidingWindowRateLimiter(redis);
    }

    @Test
    void allowsWhenUnderLimit() {
        when(redis.execute(any(DefaultRedisScript.class), eq(List.of("ratelimit:ip:1.1.1.1:auth:send-code:123")),
                eq("5"), eq("60"))).thenReturn(List.of(1L, 0L));

        RateLimitResult result = limiter.tryAcquire("ratelimit:ip:1.1.1.1:auth:send-code:123", 5, 60);

        assertThat(result.permitted()).isTrue();
    }

    @Test
    void rejectsWithRetryAfterWhenOverLimit() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), eq("5"), eq("60")))
                .thenReturn(List.of(0L, 45L));

        RateLimitResult result = limiter.tryAcquire("ratelimit:ip:1.1.1.1:auth:send-code:123", 5, 60);

        assertThat(result.permitted()).isFalse();
        assertThat(result.retryAfterSeconds()).isEqualTo(45);
    }

    @Test
    void buildsWindowScopedKeys() {
        String key = RateLimitKeys.build(RateLimitDimension.IP, "127.0.0.1", "auth:login", 60);
        assertThat(key).startsWith("ratelimit:ip:127.0.0.1:auth_login:");
    }

    @Test
    void passesMaxAndWindowToLua() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), eq("10"), eq("60")))
                .thenReturn(List.of(1L, 0L));

        limiter.tryAcquire("ratelimit:user:42:comments:create:999", 10, 60);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(DefaultRedisScript.class), keysCaptor.capture(), eq("10"), eq("60"));
        assertThat(keysCaptor.getValue()).containsExactly("ratelimit:user:42:comments:create:999");
    }
}
