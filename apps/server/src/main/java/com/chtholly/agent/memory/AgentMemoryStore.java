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

/** 按 userId + 前端会话 id 将对话记忆持久化到 Redis List（RPUSH + LTRIM），Caffeine 热数据加速。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentMemoryStore {

    private static final String KEY_PREFIX = "agent:memory:";
    /** 本地热数据缓存容量（会话数上限）。 */
    private static final int LOCAL_CACHE_MAX_SIZE = 4096;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;
    private final Cache<String, List<AgentTurn>> localCache;

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

    /** 加载指定前端会话的对话记忆（优先 Caffeine，未命中则 LRANGE Redis List）。 */
    public AgentConversationMemory getOrCreateMemory(long userId, String chatSessionId) {
        String cacheKey = cacheKey(userId, chatSessionId);
        List<AgentTurn> turns = localCache.get(cacheKey, k -> loadTurnsFromRedis(userId, chatSessionId));
        if (turns == null) {
            turns = List.of();
        }
        return new AgentConversationMemory(userId, chatSessionId, turns, this);
    }

    /** RPUSH 单条 turn 并 LTRIM 保留最近 maxTurns 条。 */
    public void addTurn(long userId, String chatSessionId, AgentTurn turn) {
        if (turn == null || turn.content() == null || turn.content().isBlank()) {
            return;
        }
        String redisKey = redisKey(userId, chatSessionId);
        String cacheKey = cacheKey(userId, chatSessionId);
        try {
            String json = objectMapper.writeValueAsString(turn);
            redis.opsForList().rightPush(redisKey, json);
            int max = maxTurns();
            redis.opsForList().trim(redisKey, -max, -1);
            redis.expire(redisKey, ttl());

            List<AgentTurn> cached = new ArrayList<>(
                    localCache.get(cacheKey, k -> loadTurnsFromRedis(userId, chatSessionId)));
            cached.add(turn);
            while (cached.size() > max) {
                cached.remove(0);
            }
            localCache.put(cacheKey, List.copyOf(cached));
        } catch (Exception e) {
            log.warn("Agent 记忆 RPUSH 失败 userId={}, sessionId={}: {}", userId, chatSessionId, e.getMessage());
        }
    }

    /** 清空指定前端会话的对话记忆。 */
    public void clearMemory(long userId, String chatSessionId) {
        redis.delete(redisKey(userId, chatSessionId));
        localCache.invalidate(cacheKey(userId, chatSessionId));
    }

    /**
     * Returns a snapshot of turns for a frontend chat session.
     *
     * @param userId        Authenticated user ID.
     * @param chatSessionId Frontend chat session ID.
     * @return Immutable turn snapshot.
     */
    public List<AgentTurn> getTurns(long userId, String chatSessionId) {
        String cacheKey = cacheKey(userId, chatSessionId);
        List<AgentTurn> turns = localCache.get(cacheKey, k -> loadTurnsFromRedis(userId, chatSessionId));
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        return List.copyOf(turns);
    }

    /** 当前本地缓存中的活跃 session 数与总记忆轮数（近似值，不含仅存在于 Redis 的冷数据）。 */
    public AgentMemoryStats getStats() {
        long activeSessions = localCache.estimatedSize();
        long totalTurns = localCache.asMap().values().stream()
                .mapToLong(List::size)
                .sum();
        return new AgentMemoryStats(activeSessions, totalTurns);
    }

    int maxTurns() {
        return Math.max(2, properties.getMemoryMaxTurns());
    }

    private List<AgentTurn> loadTurnsFromRedis(long userId, String chatSessionId) {
        String redisKey = redisKey(userId, chatSessionId);
        List<String> raw = redis.opsForList().range(redisKey, 0, -1);
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
                log.warn("Agent 记忆条目反序列化失败 userId={}, sessionId={}: {}",
                        userId, chatSessionId, e.getMessage());
            }
        }
        redis.expire(redisKey, ttl());
        return List.copyOf(turns);
    }

    private Duration ttl() {
        return Duration.ofMinutes(Math.max(5, properties.getMemoryTtlMinutes()));
    }

    private static String cacheKey(long userId, String chatSessionId) {
        return userId + ":" + chatSessionId;
    }

    private static String redisKey(long userId, String chatSessionId) {
        return KEY_PREFIX + userId + ":" + chatSessionId;
    }
}
