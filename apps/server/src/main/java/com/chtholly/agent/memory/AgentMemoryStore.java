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

/** 按 userId + 前端会话 id 将对话记忆持久化到 Redis List（RPUSH + LTRIM），Caffeine 热数据加速。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentMemoryStore {

    private static final String KEY_PREFIX = "agent:memory:";
    private static final String EPOCH_KEY_PREFIX = "agent:memory:epoch:";
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

    /** 校验持久 epoch 后加载会话记忆；本地快照版本一致时复用 Caffeine，否则重读 Redis List。 */
    public AgentConversationMemory getOrCreateMemory(long userId, String chatSessionId) {
        CachedMemory snapshot = currentMemory(userId, chatSessionId);
        return new AgentConversationMemory(
                userId,
                chatSessionId,
                snapshot.epoch(),
                snapshot.turns(),
                this);
    }

    /** RPUSH 单条 turn 并 LTRIM 保留最近 maxTurns 条。 */
    public boolean addTurn(long userId, String chatSessionId, AgentTurn turn) {
        if (turn == null) {
            return false;
        }
        long expectedEpoch = currentEpoch(userId, chatSessionId);
        return addTurns(
                userId,
                chatSessionId,
                List.of(turn),
                null,
                UNFENCED_DEADLINE_EPOCH_MS,
                expectedEpoch).committed();
    }

    MemoryWriteResult addTurn(
            long userId,
            String chatSessionId,
            AgentTurn turn,
            long expectedEpoch) {
        if (turn == null) {
            return MemoryWriteResult.rejected("INVALID_EXCHANGE");
        }
        return addTurns(
                userId,
                chatSessionId,
                List.of(turn),
                null,
                UNFENCED_DEADLINE_EPOCH_MS,
                expectedEpoch);
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
        long expectedEpoch = currentEpoch(userId, chatSessionId);
        return addTurns(userId, chatSessionId, turns, control, deadlineEpochMs, expectedEpoch);
    }

    MemoryWriteResult addTurns(
            long userId,
            String chatSessionId,
            List<AgentTurn> turns,
            AgentTurnControl control,
            long deadlineEpochMs,
            long expectedEpoch) {
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
            args[4] = Long.toString(expectedEpoch);
            System.arraycopy(serialized, 0, args, 5, serialized.length);
            Long code = redis.execute(
                    APPEND_SCRIPT,
                    List.of(
                            redisKey,
                            AgentTurnKeySupport.activeKey(userId, chatSessionId),
                            epochKey(userId, chatSessionId)),
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
            Long nextEpoch = redis.execute(
                    CLEAR_SCRIPT,
                    List.of(redisKey(userId, chatSessionId), epochKey(userId, chatSessionId)));
            if (nextEpoch == null || nextEpoch < 1L) {
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

    private static String epochKey(long userId, String chatSessionId) {
        return EPOCH_KEY_PREFIX + userId + ":" + chatSessionId;
    }

    private CachedMemory currentMemory(long userId, String chatSessionId) {
        String cacheKey = cacheKey(userId, chatSessionId);
        long epoch = currentEpoch(userId, chatSessionId);
        CachedMemory cached = localCache.getIfPresent(cacheKey);
        if (cached != null && cached.epoch() == epoch) {
            return cached;
        }
        CachedMemory loaded = new CachedMemory(epoch, loadTurnsFromRedis(userId, chatSessionId));
        localCache.put(cacheKey, loaded);
        return loaded;
    }

    private long currentEpoch(long userId, String chatSessionId) {
        String raw = redis.opsForValue().get(epochKey(userId, chatSessionId));
        if (!StringUtils.hasText(raw)) {
            return 0L;
        }
        try {
            long epoch = Long.parseLong(raw);
            if (epoch < 0L) {
                throw new NumberFormatException("negative epoch");
            }
            return epoch;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Agent memory epoch is invalid", exception);
        }
    }

    private static DefaultRedisScript<Long> appendScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                local maxTurns = tonumber(ARGV[1])
                local ttlMs = tonumber(ARGV[2])
                local deadlineMs = tonumber(ARGV[3])
                local expectedTurnId = ARGV[4]
                local expectedEpoch = tonumber(ARGV[5])
                if not maxTurns or maxTurns < 2 or not ttlMs or ttlMs < 1
                        or not deadlineMs or not expectedEpoch or expectedEpoch < 0 or #ARGV < 6 then
                  return redis.error_reply('invalid memory arguments')
                end
                local currentEpoch = tonumber(redis.call('GET', KEYS[3]) or '0')
                if currentEpoch ~= expectedEpoch then
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
                return 1
                """);
        return script;
    }

    private static DefaultRedisScript<Long> clearScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                local nextEpoch = redis.call('INCR', KEYS[2])
                redis.call('DEL', KEYS[1])
                return nextEpoch
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

    private record CachedMemory(long epoch, List<AgentTurn> turns) {
        private CachedMemory {
            turns = turns == null ? List.of() : List.copyOf(turns);
        }
    }
}
