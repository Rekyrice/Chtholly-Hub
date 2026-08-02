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
    private static final long UNFENCED_DEADLINE_EPOCH_MS = 253_402_300_799_999L;
    private static final DefaultRedisScript<Long> APPEND_SCRIPT = appendScript();
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
        if (turn == null) {
            return;
        }
        addTurns(userId, chatSessionId, List.of(turn));
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
        List<AgentTurn> cachedBeforeWrite = localCache.getIfPresent(cacheKey);
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
            Object[] args = new Object[4 + serialized.length];
            args[0] = Integer.toString(max);
            args[1] = Long.toString(ttl().toMillis());
            args[2] = Long.toString(effectiveDeadlineEpochMs);
            args[3] = expectedTurnId;
            System.arraycopy(serialized, 0, args, 4, serialized.length);
            Long code = redis.execute(
                    APPEND_SCRIPT,
                    List.of(redisKey, AgentTurnKeySupport.activeKey(userId, chatSessionId)),
                    args);
            MemoryWriteResult result = MemoryWriteResult.fromCode(code);
            if (!result.committed()) {
                if (result.status() == MemoryWriteStatus.UNKNOWN) {
                    localCache.invalidate(cacheKey);
                }
                return result;
            }

            if (cachedBeforeWrite != null) {
                List<AgentTurn> cached = new ArrayList<>(cachedBeforeWrite);
                cached.addAll(validTurns);
                while (cached.size() > max) {
                    cached.remove(0);
                }
                localCache.put(cacheKey, List.copyOf(cached));
            }
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

    private static DefaultRedisScript<Long> appendScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                local maxTurns = tonumber(ARGV[1])
                local ttlMs = tonumber(ARGV[2])
                local deadlineMs = tonumber(ARGV[3])
                local expectedTurnId = ARGV[4]
                if not maxTurns or maxTurns < 2 or not ttlMs or ttlMs < 1
                        or not deadlineMs or #ARGV < 5 then
                  return redis.error_reply('invalid memory arguments')
                end
                if expectedTurnId ~= '' and redis.call('GET', KEYS[2]) ~= expectedTurnId then
                  return -2
                end
                local now = redis.call('TIME')
                local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
                if nowMs >= deadlineMs then
                  return -1
                end
                redis.call('RPUSH', KEYS[1], unpack(ARGV, 5))
                redis.call('LTRIM', KEYS[1], -maxTurns, -1)
                redis.call('PEXPIRE', KEYS[1], ttlMs)
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
}
