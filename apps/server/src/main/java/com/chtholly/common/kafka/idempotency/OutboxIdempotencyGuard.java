package com.chtholly.common.kafka.idempotency;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Outbox 事件消费幂等守卫：基于 Redis 防止 Kafka 重投产生副作用。
 */
@Component
@RequiredArgsConstructor
public class OutboxIdempotencyGuard {

    private static final String KEY_PREFIX = "consumed:outbox:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    /**
     * 判断 outbox 事件是否已成功消费过。
     */
    public boolean isAlreadyConsumed(String consumerScope, long eventId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(consumerScope, eventId)));
    }

    /**
     * 在单行处理成功后标记 outbox 事件已消费。
     */
    public void markConsumed(String consumerScope, long eventId) {
        redisTemplate.opsForValue().set(key(consumerScope, eventId), "1", TTL);
    }

    private String key(String consumerScope, long eventId) {
        return KEY_PREFIX + consumerScope + ":" + eventId;
    }
}
