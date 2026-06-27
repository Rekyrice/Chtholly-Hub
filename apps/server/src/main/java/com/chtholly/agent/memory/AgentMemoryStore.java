package com.chtholly.agent.memory;

import com.chtholly.agent.config.AgentProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

/** 按用户 ID 将会话记忆持久化到 Redis（滑动 TTL）。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentMemoryStore {

    private static final String KEY_PREFIX = "agent:conv:";
    private static final TypeReference<List<AgentTurn>> TURN_LIST_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;

    public AgentMemoryStore(StringRedisTemplate redis, ObjectMapper objectMapper, AgentProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 读取用户会话记忆；命中时刷新 TTL（滑动过期）。 */
    public AgentConversationMemory getOrCreate(long userId) {
        String key = key(userId);
        String json = redis.opsForValue().get(key);
        if (!StringUtils.hasText(json)) {
            return new AgentConversationMemory(properties.getMemoryMaxTurns());
        }
        try {
            List<AgentTurn> turns = objectMapper.readValue(json, TURN_LIST_TYPE);
            touchTtl(key);
            return AgentConversationMemory.restore(turns, properties.getMemoryMaxTurns());
        } catch (Exception e) {
            log.warn("Agent 记忆反序列化失败 userId={}: {}", userId, e.getMessage());
            redis.delete(key);
            return new AgentConversationMemory(properties.getMemoryMaxTurns());
        }
    }

    /** 写回 Redis 并刷新 TTL。 */
    public void save(long userId, AgentConversationMemory memory) {
        if (memory == null || memory.isEmpty()) {
            clear(userId);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(memory.snapshot());
            redis.opsForValue().set(key(userId), json, ttl());
        } catch (Exception e) {
            log.warn("Agent 记忆写入 Redis 失败 userId={}: {}", userId, e.getMessage());
        }
    }

    /** 清空用户会话记忆。 */
    public void clear(long userId) {
        redis.delete(key(userId));
    }

    private void touchTtl(String key) {
        redis.expire(key, ttl());
    }

    private Duration ttl() {
        int minutes = Math.max(5, properties.getMemoryTtlMinutes());
        return Duration.ofMinutes(minutes);
    }

    private static String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
