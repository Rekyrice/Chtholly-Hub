package com.chtholly.agent.proactive;

import com.chtholly.agent.config.AgentExtensionGroup;
import com.chtholly.agent.config.ConditionalOnAgentExtensions;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;

/**
 * 限制每位用户每日最多接收的主动推送条数。
 */
@Component
@ConditionalOnAgentExtensions({AgentExtensionGroup.PROACTIVE, AgentExtensionGroup.EXPERIENCE,
        AgentExtensionGroup.COMMUNITY_ACTIONS})
public class ProactiveRateLimiter {

    static final int DAILY_LIMIT = 3;
    private static final String KEY_PREFIX = "agent:proactive:daily:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public ProactiveRateLimiter(StringRedisTemplate redis) {
        this(redis, Clock.systemDefaultZone());
    }

    ProactiveRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public int totalSentToday(long userId) {
        String raw = redis.opsForValue().get(dailyKey(userId));
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public boolean canSend(long userId) {
        return totalSentToday(userId) < DAILY_LIMIT;
    }

    public void recordSend(long userId) {
        String key = dailyKey(userId);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofDays(2));
        }
    }

    private String dailyKey(long userId) {
        return KEY_PREFIX + userId + ":" + LocalDate.now(clock);
    }
}
