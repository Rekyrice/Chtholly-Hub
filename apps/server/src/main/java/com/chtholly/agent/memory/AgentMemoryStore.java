package com.chtholly.agent.memory;

import com.chtholly.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 按 userId 将对话记忆持久化到 Redis List（RPUSH + LTRIM），Caffeine 热数据加速。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentMemoryStore {

    private static final String KEY_PREFIX = "agent:memory:";
    /** 本地热数据缓存容量（用户数上限）。 */
    private static final int LOCAL_CACHE_MAX_SIZE = 1024;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;
    private final Cache<Long, List<AgentTurn>> localCache;

    public AgentMemoryStore(StringRedisTemplate redis, ObjectMapper objectMapper, AgentProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        int ttlMinutes = Math.max(5, properties.getMemoryTtlMinutes());
        this.localCache = Caffeine.newBuilder()
                .maximumSize(LOCAL_CACHE_MAX_SIZE)
                .expireAfterAccess(Duration.ofMinutes(ttlMinutes))
                .build();
    }

    /** 加载用户对话记忆（优先 Caffeine，未命中则 LRANGE Redis List）。 */
    public AgentConversationMemory getOrCreateMemory(long userId) {
        List<AgentTurn> turns = localCache.get(userId, this::loadTurnsFromRedis);
        if (turns == null) {
            turns = List.of();
        }
        return new AgentConversationMemory(userId, turns, this);
    }

    /** RPUSH 单条 turn 并 LTRIM 保留最近 maxTurns 条。 */
    public void addTurn(long userId, AgentTurn turn) {
        if (turn == null || turn.content() == null || turn.content().isBlank()) {
            return;
        }
        String key = key(userId);
        try {
            String json = objectMapper.writeValueAsString(turn);
            redis.opsForList().rightPush(key, json);
            int max = maxTurns();
            redis.opsForList().trim(key, -max, -1);
            redis.expire(key, ttl());

            List<AgentTurn> cached = new ArrayList<>(localCache.get(userId, id -> loadTurnsFromRedis(id)));
            cached.add(turn);
            while (cached.size() > max) {
                cached.remove(0);
            }
            localCache.put(userId, List.copyOf(cached));
        } catch (Exception e) {
            log.warn("Agent 记忆 RPUSH 失败 userId={}: {}", userId, e.getMessage());
        }
    }

    /** 清空用户对话记忆。 */
    public void clearMemory(long userId) {
        redis.delete(key(userId));
        localCache.invalidate(userId);
    }

    int maxTurns() {
        return Math.max(2, properties.getMemoryMaxTurns());
    }

    private List<AgentTurn> loadTurnsFromRedis(long userId) {
        String key = key(userId);
        List<String> raw = redis.opsForList().range(key, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<AgentTurn> turns = new ArrayList<>(raw.size());
        for (String item : raw) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            try {
                turns.add(objectMapper.readValue(item, AgentTurn.class));
            } catch (Exception e) {
                log.warn("Agent 记忆条目反序列化失败 userId={}: {}", userId, e.getMessage());
            }
        }
        redis.expire(key, ttl());
        return List.copyOf(turns);
    }

    private Duration ttl() {
        return Duration.ofMinutes(Math.max(5, properties.getMemoryTtlMinutes()));
    }

    private static String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
