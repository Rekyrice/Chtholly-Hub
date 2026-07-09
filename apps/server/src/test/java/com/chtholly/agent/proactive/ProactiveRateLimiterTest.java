package com.chtholly.agent.proactive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProactiveRateLimiterTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private ProactiveRateLimiter limiter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        limiter = new ProactiveRateLimiter(redis, Clock.fixed(Instant.parse("2026-07-09T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void given_underDailyLimit_when_canSend_then_true() {
        when(valueOps.get("agent:proactive:daily:9:2026-07-09")).thenReturn("2");

        assertThat(limiter.canSend(9L)).isTrue();
        assertThat(limiter.totalSentToday(9L)).isEqualTo(2);
    }

    @Test
    void given_reachedDailyLimit_when_canSend_then_false() {
        when(valueOps.get("agent:proactive:daily:9:2026-07-09")).thenReturn("3");

        assertThat(limiter.canSend(9L)).isFalse();
    }
}
