package com.chtholly.common.kafka.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxIdempotencyGuardTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private OutboxIdempotencyGuard guard;

    @BeforeEach
    void setUp() {
        guard = new OutboxIdempotencyGuard(redisTemplate);
    }

    @Test
    void detectsAlreadyConsumedEvent() {
        when(redisTemplate.hasKey("consumed:outbox:search:42")).thenReturn(true);

        assertThat(guard.isAlreadyConsumed("search", 42)).isTrue();
    }

    @Test
    void marksConsumedWithScopeAndTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        guard.markConsumed("relation", 42);

        verify(valueOps).set("consumed:outbox:relation:42", "1", Duration.ofHours(24));
    }
}
