package com.chtholly.agent.memory;

import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.runtime.AgentTurnControl;
import com.chtholly.agent.runtime.AgentTurnKeySupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 按 userId + 前端会话 id 将对话记忆持久化到 Redis List（RPUSH + LTRIM），Caffeine 热数据加速。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentMemoryStore {

    private static final String KEY_PREFIX = "agent:memory:";
    private static final String GENERATION_KEY_PREFIX = "agent:memory:generation:";
    private static final int GENERATION_INIT_ATTEMPTS = 3;
    private static final long UNFENCED_DEADLINE_EPOCH_MS = 253_402_300_799_999L;
    private static final DefaultRedisScript<Long> APPEND_SCRIPT = appendScript();
    private static final DefaultRedisScript<Long> CLEAR_SCRIPT = clearScript();
    /** 本地热数据缓存容量（会话数上限）。 */
    private static final int LOCAL_CACHE_MAX_SIZE = 4096;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;
    private final Cache<String, CachedMemory> localCache;

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

    /** 校验有限期 generation 后加载会话记忆；本地快照版本一致时复用 Caffeine，否则重读 Redis List。 */
    public AgentConversationMemory getOrCreateMemory(long userId, String chatSessionId) {
        CachedMemory snapshot = currentMemory(userId, chatSessionId);
        return new AgentConversationMemory(
                userId,
                chatSessionId,
                snapshot.generation(),
                snapshot.turns(),
                this);
    }

    /** RPUSH 单条 turn 并 LTRIM 保留最近 maxTurns 条。 */
    public boolean addTurn(long userId, String chatSessionId, AgentTurn turn) {
        if (turn == null) {
            return false;
        }
        String expectedGeneration = currentGeneration(userId, chatSessionId);
        return addTurns(
                userId,
                chatSessionId,
                List.of(turn),
                null,
                UNFENCED_DEADLINE_EPOCH_MS,
                expectedGeneration).committed();
    }

    MemoryWriteResult addTurn(
            long userId,
            String chatSessionId,
            AgentTurn turn,
            String expectedGeneration) {
        if (turn == null) {
            return MemoryWriteResult.rejected("INVALID_EXCHANGE");
        }
        return addTurns(
                userId,
                chatSessionId,
                List.of(turn),
                null,
                UNFENCED_DEADLINE_EPOCH_MS,
                expectedGeneration);
    }

    /** 使用单次 RPUSH 原子追加一组 turn，避免问答对只写入一半。 */
    boolean addTurns(long userId, String chatSessionId, List<AgentTurn> turns) {
        return addTurns(userId, chatSessionId, turns, null).committed();
    }

    /** Atomically appends, trims, and expires an exchange guarded by the accepted turn lease. */
    MemoryWriteResult addTurns(
            long userId,
            String chatSessionId,
            List<AgentTurn> turns,
            AgentTurnControl control) {
        long deadlineEpochMs = control == null
                ? UNFENCED_DEADLINE_EPOCH_MS
                : control.budget().deadlineEpochMillis();
        return addTurns(userId, chatSessionId, turns, control, deadlineEpochMs);
    }

    MemoryWriteResult addTurns(
            long userId,
            String chatSessionId,
            List<AgentTurn> turns,
            AgentTurnControl control,
            long deadlineEpochMs) {
        String expectedGeneration = currentGeneration(userId, chatSessionId);
        return addTurns(userId, chatSessionId, turns, control, deadlineEpochMs, expectedGeneration);
    }

    MemoryWriteResult addTurns(
            long userId,
            String chatSessionId,
            List<AgentTurn> turns,
            AgentTurnControl control,
            long deadlineEpochMs,
            String expectedGeneration) {
        List<AgentTurn> validTurns = turns == null
                ? List.of()
                : turns.stream()
                        .filter(java.util.Objects::nonNull)
                        .filter(turn -> turn.content() != null && !turn.content().isBlank())
                        .toList();
        if (validTurns.isEmpty()) {
            return MemoryWriteResult.rejected("INVALID_EXCHANGE");
        }
        if (Thread.currentThread().isInterrupted()) {
            return MemoryWriteResult.rejected("CALL_CANCELLED");
        }
        String redisKey = redisKey(userId, chatSessionId);
        String cacheKey = cacheKey(userId, chatSessionId);
        try {
            String[] serialized = new String[validTurns.size()];
            for (int index = 0; index < validTurns.size(); index++) {
                serialized[index] = objectMapper.writeValueAsString(validTurns.get(index));
            }
            int max = maxTurns();
            boolean fenced = control != null && !"direct".equals(control.connectionId());
            String expectedTurnId = fenced ? control.turnId() : "";
            long effectiveDeadlineEpochMs = fenced
                    ? Math.min(control.budget().deadlineEpochMillis(), deadlineEpochMs)
                    : UNFENCED_DEADLINE_EPOCH_MS;
            Object[] args = new Object[5 + serialized.length];
            args[0] = Integer.toString(max);
            args[1] = Long.toString(ttl().toMillis());
            args[2] = Long.toString(effectiveDeadlineEpochMs);
            args[3] = expectedTurnId;
            args[4] = expectedGeneration;
            System.arraycopy(serialized, 0, args, 5, serialized.length);
            Long code = redis.execute(
                    APPEND_SCRIPT,
                    List.of(
                            redisKey,
                            AgentTurnKeySupport.activeKey(userId, chatSessionId),
                            generationKey(userId, chatSessionId)),
                    args);
            MemoryWriteResult result = MemoryWriteResult.fromCode(code);
            if (!result.committed()) {
                if (result.status() == MemoryWriteStatus.UNKNOWN
                        || "SESSION_CLEARED".equals(result.failureCode())) {
                    localCache.invalidate(cacheKey);
                }
                return result;
            }
            localCache.invalidate(cacheKey);
            return result;
        } catch (Exception e) {
            localCache.invalidate(cacheKey);
            log.warn("Agent memory script outcome unknown userId={}, sessionId={}: {}",
                    userId, chatSessionId, e.getMessage());
            return MemoryWriteResult.unknown("REDIS_UNAVAILABLE");
        }
    }

    /** 清空指定前端会话的对话记忆。 */
    public void clearMemory(long userId, String chatSessionId) {
        String cacheKey = cacheKey(userId, chatSessionId);
        try {
            Long result = redis.execute(
                    CLEAR_SCRIPT,
                    List.of(
                            redisKey(userId, chatSessionId),
                            generationKey(userId, chatSessionId),
                            AgentTurnKeySupport.activeKey(userId, chatSessionId)),
                    UUID.randomUUID().toString(),
                    Long.toString(ttl().toMillis()));
            if (result == null || (result != 0L && result != 1L)) {
                throw new IllegalStateException("Agent memory clear outcome is unknown");
            }
        } finally {
            localCache.invalidate(cacheKey);
        }
    }

    /**
     * Returns a snapshot of turns for a frontend chat session.
     *
     * @param userId        Authenticated user ID.
     * @param chatSessionId Frontend chat session ID.
     * @return Immutable turn snapshot.
     */
    public List<AgentTurn> getTurns(long userId, String chatSessionId) {
        List<AgentTurn> turns = currentMemory(userId, chatSessionId).turns();
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        return List.copyOf(turns);
    }

    /** 当前本地缓存中的活跃 session 数与总记忆轮数（近似值，不含仅存在于 Redis 的冷数据）。 */
    public AgentMemoryStats getStats() {
        long activeSessions = localCache.estimatedSize();
        long totalTurns = localCache.asMap().values().stream()
                .mapToLong(cached -> cached.turns().size())
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
        redis.expire(generationKey(userId, chatSessionId), ttl());
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

    private static String generationKey(long userId, String chatSessionId) {
        return GENERATION_KEY_PREFIX + userId + ":" + chatSessionId;
    }

    private CachedMemory currentMemory(long userId, String chatSessionId) {
        String cacheKey = cacheKey(userId, chatSessionId);
        String generation = currentGeneration(userId, chatSessionId);
        CachedMemory cached = localCache.getIfPresent(cacheKey);
        if (cached != null && cached.generation().equals(generation)) {
            return cached;
        }
        CachedMemory loaded = new CachedMemory(
                generation,
                loadTurnsFromRedis(userId, chatSessionId));
        localCache.put(cacheKey, loaded);
        return loaded;
    }

    private String currentGeneration(long userId, String chatSessionId) {
        String key = generationKey(userId, chatSessionId);
        for (int attempt = 0; attempt < GENERATION_INIT_ATTEMPTS; attempt++) {
            String current = redis.opsForValue().get(key);
            if (StringUtils.hasText(current)) {
                return current;
            }
            String candidate = UUID.randomUUID().toString();
            Boolean created = redis.opsForValue().setIfAbsent(key, candidate, ttl());
            if (Boolean.TRUE.equals(created)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Agent memory generation could not be initialized");
    }

    private static DefaultRedisScript<Long> appendScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                local maxTurns = tonumber(ARGV[1])
                local ttlMs = tonumber(ARGV[2])
                local deadlineMs = tonumber(ARGV[3])
                local expectedTurnId = ARGV[4]
                local expectedGeneration = ARGV[5]
                if not maxTurns or maxTurns < 2 or not ttlMs or ttlMs < 1
                        or not deadlineMs or expectedGeneration == '' or #ARGV < 6 then
                  return redis.error_reply('invalid memory arguments')
                end
                local currentGeneration = redis.call('GET', KEYS[3])
                if not currentGeneration or currentGeneration ~= expectedGeneration then
                  return -3
                end
                if expectedTurnId ~= '' and redis.call('GET', KEYS[2]) ~= expectedTurnId then
                  return -2
                end
                local now = redis.call('TIME')
                local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
                if nowMs >= deadlineMs then
                  return -1
                end
                redis.call('RPUSH', KEYS[1], unpack(ARGV, 6))
                redis.call('LTRIM', KEYS[1], -maxTurns, -1)
                redis.call('PEXPIRE', KEYS[1], ttlMs)
                redis.call('PEXPIRE', KEYS[3], ttlMs)
                return 1
                """);
        return script;
    }

    private static DefaultRedisScript<Long> clearScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                if redis.call('EXISTS', KEYS[1]) == 0
                        and redis.call('EXISTS', KEYS[2]) == 0
                        and redis.call('EXISTS', KEYS[3]) == 0 then
                  return 0
                end
                redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
                redis.call('DEL', KEYS[1])
                return 1
                """);
        return script;
    }

    /** Stable outcome of the atomic Redis memory script. */
    public record MemoryWriteResult(MemoryWriteStatus status, String failureCode) {

        public MemoryWriteResult {
            status = status == null ? MemoryWriteStatus.UNKNOWN : status;
            failureCode = failureCode == null ? "" : failureCode;
        }

        public boolean committed() {
            return status == MemoryWriteStatus.COMMITTED;
        }

        static MemoryWriteResult fromCode(Long code) {
            if (code == null) {
                return unknown("EMPTY_RESULT");
            }
            if (code == 1L) {
                return new MemoryWriteResult(MemoryWriteStatus.COMMITTED, "");
            }
            if (code == -1L) {
                return rejected("DEADLINE_EXPIRED");
            }
            if (code == -2L) {
                return rejected("STALE_TURN");
            }
            if (code == -3L) {
                return rejected("SESSION_CLEARED");
            }
            return unknown("UNEXPECTED_RESULT");
        }

        static MemoryWriteResult rejected(String code) {
            return new MemoryWriteResult(MemoryWriteStatus.REJECTED, code);
        }

        static MemoryWriteResult unknown(String code) {
            return new MemoryWriteResult(MemoryWriteStatus.UNKNOWN, code);
        }
    }

    public enum MemoryWriteStatus {
        COMMITTED,
        REJECTED,
        UNKNOWN
    }

    private record CachedMemory(String generation, List<AgentTurn> turns) {
        private CachedMemory {
            if (!StringUtils.hasText(generation)) {
                throw new IllegalArgumentException("generation must not be blank");
            }
            turns = turns == null ? List.of() : List.copyOf(turns);
        }
    }
}
