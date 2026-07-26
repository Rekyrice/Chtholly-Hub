package com.chtholly.counter.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.mapper.CounterReactionMapper;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.service.CounterReactionCommandService;
import com.chtholly.counter.service.CounterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Counter command facade and Redis-backed online read service.
 */
@Slf4j
@Service
public class CounterServiceImpl implements CounterService {

    private static final int MYSQL_MEMBERSHIP_BATCH_SIZE = 500;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> effectiveCountScript;
    private final CounterReactionCommandService reactionCommandService;
    private final CounterReactionMapper reactionMapper;
    private final CounterReactionProjectionStore reactionProjectionStore;
    private final CounterCalibrationService calibrationService;

    public CounterServiceImpl(StringRedisTemplate redis,
                              CounterReactionCommandService reactionCommandService,
                              CounterReactionMapper reactionMapper,
                              CounterReactionProjectionStore reactionProjectionStore,
                              CounterCalibrationService calibrationService) {
        this.redis = redis;
        this.reactionCommandService = reactionCommandService;
        this.reactionMapper = reactionMapper;
        this.reactionProjectionStore = reactionProjectionStore;
        this.calibrationService = calibrationService;
        this.effectiveCountScript = new DefaultRedisScript<>();
        this.effectiveCountScript.setResultType(Long.class);
        this.effectiveCountScript.setScriptText(EFFECTIVE_COUNT_LUA);
    }

    /**
     * Adds one authoritative like relation.
     *
     * @return {@code true} only when the MySQL relation changed
     */
    @Override
    public boolean like(String entityType, String entityId, long userId) {
        return reactionCommandService.setReaction(entityType, entityId, "like", userId, true);
    }

    /** Removes one authoritative like relation. */
    @Override
    public boolean unlike(String entityType, String entityId, long userId) {
        return reactionCommandService.setReaction(entityType, entityId, "like", userId, false);
    }

    /** Adds one authoritative favorite relation. */
    @Override
    public boolean fav(String entityType, String entityId, long userId) {
        return reactionCommandService.setReaction(entityType, entityId, "fav", userId, true);
    }

    /** Removes one authoritative favorite relation. */
    @Override
    public boolean unfav(String entityType, String entityId, long userId) {
        return reactionCommandService.setReaction(entityType, entityId, "fav", userId, false);
    }

    /**
     * Returns aggregated counts from SDS and rebuilds missing reaction fields from MySQL facts.
     *
     * @param metrics Subset of metrics to read (e.g. "like", "fav").
     */
    @Override
    public Map<String, Long> getCounts(String entityType, String entityId, List<String> metrics) {
        CounterSchema.requirePersistableIdentity(entityType, entityId);
        String sdsKey = CounterKeys.sdsKey(entityType, entityId);
        int expectedLen = CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE;
        byte[] raw = getRaw(sdsKey);
        Map<String, Long> result = new LinkedHashMap<>();

        if (raw == null || raw.length != expectedLen) {
            if (metrics.contains("view")) {
                result.put("view", getEffectiveCount(entityType, entityId, "view"));
            }
            boolean needsReaction = metrics.stream()
                    .anyMatch(metric -> "like".equals(metric) || "fav".equals(metric));
            if (!needsReaction) {
                return result;
            }
            try {
                calibrationService.reconcileEntity(entityType, entityId);
            } catch (RuntimeException exception) {
                log.warn("Counter read reconciliation failed entityType={} entityId={}: {}",
                        entityType, entityId, exception.getMessage());
            }
            raw = getRaw(sdsKey);
            if (raw == null || raw.length != expectedLen) {
                throw new BusinessException(
                        ErrorCode.INTERNAL_ERROR,
                        "互动计数暂时不可用，请稍后重试",
                        HttpStatus.SERVICE_UNAVAILABLE.value());
            }
        }

        for (String metric : metrics) {
            if (result.containsKey(metric)) { continue; }
            Integer index = CounterSchema.NAME_TO_IDX.get(metric);
            if (index != null) {
                result.put(metric, readInt32BE(raw, index * CounterSchema.FIELD_SIZE));
            }
        }
        return result;
    }

    /**
     * Atomically reads SDS and the pending aggregation hash in one Redis script.
     *
     * <p>The flush path increments SDS before decrementing the aggregation hash. An atomic read
     * can therefore observe the old exact total, a temporary high estimate, or the new exact
     * total, but never the unsafe old-SDS/new-aggregation combination that would undercount.
     */
    @Override
    public long getEffectiveCount(String entityType, String entityId, String metric) {
        Integer index = CounterSchema.NAME_TO_IDX.get(metric);
        if (index == null) {
            throw new IllegalArgumentException("Unsupported counter metric: " + metric);
        }
        Long value = redis.execute(
                effectiveCountScript,
                List.of(CounterKeys.sdsKey(entityType, entityId), CounterKeys.aggKey(entityType, entityId)),
                String.valueOf(index),
                String.valueOf(CounterSchema.FIELD_SIZE),
                String.valueOf(CounterSchema.SCHEMA_LEN));
        return value == null ? 0L : Math.max(0L, value);
    }

    /**
     * 批量获取实体计数（管道批量 GET 降低 RTT）。
     * reaction SDS 缺失或结构异常时从 MySQL 关系事实校准；恢复失败则返回 503。
     * @param entityType 实体类型
     * @param entityIds 实体ID列表
     * @param metrics 指标名列表
     * @return 每个实体的指标计数映射
     */
    @Override
    public Map<String, Map<String, Long>> getCountsBatch(String entityType, List<String> entityIds, List<String> metrics) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        if (entityIds == null || entityIds.isEmpty() || metrics == null || metrics.isEmpty()) {
            return out;
        }

        List<String> keys = new ArrayList<>(entityIds.size());
        for (String eid : entityIds) {
            keys.add(CounterKeys.sdsKey(entityType, eid));
        }

        // 管道批量 GET：将多个 SDS 读取合并到一次往返
        List<Object> raws = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (String k : keys) {
                connection.stringCommands().get(k.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        int expectedLen = CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE;
        for (int i = 0; i < entityIds.size(); i++) {
            String eid = entityIds.get(i);
            Object rawObj = i < raws.size() ? raws.get(i) : null;
            byte[] raw = (rawObj instanceof byte[]) ? (byte[]) rawObj : null;

            Map<String, Long> m = new LinkedHashMap<>();
            if (raw != null && raw.length == expectedLen) {
                for (String name : metrics) {
                    Integer idx = CounterSchema.NAME_TO_IDX.get(name);
                    if (idx == null) continue;
                    int off = idx * CounterSchema.FIELD_SIZE;
                    long val = readInt32BE(raw, off);
                    m.put(name, val);
                }
            } else {
                m.putAll(getCounts(entityType, eid, metrics));
            }
            out.put(eid, m);
        }
        return out;
    }

    /** Reads the like projection and falls back to the MySQL fact when it is incomplete. */
    @Override
    public boolean isLiked(String entityType, String entityId, long userId) {
        return readReaction(entityType, entityId, "like", userId);
    }

    /** Reads the favorite projection and falls back to the MySQL fact when it is incomplete. */
    @Override
    public boolean isFaved(String entityType, String entityId, long userId) {
        return readReaction(entityType, entityId, "fav", userId);
    }

    @Override
    public Map<Long, Boolean> batchIsLiked(long userId, List<Long> postIds) {
        return batchRelationBits("like", userId, postIds);
    }

    @Override
    public Map<Long, Boolean> batchIsFaved(long userId, List<Long> postIds) {
        return batchRelationBits("fav", userId, postIds);
    }

    private Map<Long, Boolean> batchRelationBits(String metric, long userId, List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = postIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }

        List<CounterReactionKey> keys = ids.stream()
                .map(id -> new CounterReactionKey("post", String.valueOf(id), metric, userId))
                .toList();
        Map<CounterReactionKey, Optional<Boolean>> projected = reactionProjectionStore.readBatch(keys);
        List<CounterReactionKey> unknown = keys.stream()
                .filter(key -> projected.getOrDefault(key, Optional.empty()).isEmpty())
                .toList();
        Set<String> mysqlExisting = new HashSet<>();
        for (int from = 0; from < unknown.size(); from += MYSQL_MEMBERSHIP_BATCH_SIZE) {
            List<String> entityIds = unknown.subList(
                            from, Math.min(from + MYSQL_MEMBERSHIP_BATCH_SIZE, unknown.size()))
                    .stream()
                    .map(CounterReactionKey::entityId)
                    .toList();
            List<String> existing =
                    reactionMapper.findExistingEntityIds("post", metric, userId, entityIds);
            if (existing == null) {
                throw new IllegalStateException("Counter reaction MySQL fallback returned no result");
            }
            Set<String> requestedEntityIds = new HashSet<>(entityIds);
            for (String existingEntityId : existing) {
                if (!requestedEntityIds.contains(existingEntityId)) {
                    throw new IllegalStateException(
                            "Counter reaction MySQL fallback returned an unexpected entity");
                }
                mysqlExisting.add(existingEntityId);
            }
        }
        Map<Long, Boolean> out = new LinkedHashMap<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            CounterReactionKey key = keys.get(i);
            Optional<Boolean> value = projected.getOrDefault(key, Optional.empty());
            out.put(ids.get(i), value.orElseGet(() -> mysqlExisting.contains(key.entityId())));
        }
        return out;
    }

    /** Returns a projection value when complete, otherwise the current MySQL relation. */
    private boolean readReaction(String entityType, String entityId, String metric, long userId) {
        CounterReactionKey key = new CounterReactionKey(entityType, entityId, metric, userId);
        return reactionProjectionStore.read(key)
                .orElseGet(() -> reactionMapper.exists(entityType, entityId, metric, userId) == 1);
    }

    /**
     * 读取 SDS 原始字节（固定结构，长度=字段数×4）。
     */
    private byte[] getRaw(String key) {
        return redis.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 以大端序读取 32 位无符号整型。
     */
    private static long readInt32BE(byte[] buf, int off) {
        long n = 0;
        for (int i = 0; i < 4; i++) {
            n = (n << 8) | (buf[off + i] & 0xFFL);
        }
        return n;
    }

    private static final String EFFECTIVE_COUNT_LUA = """
            local cntKey = KEYS[1]
            local aggKey = KEYS[2]
            local idx = tonumber(ARGV[1])
            local fieldSize = tonumber(ARGV[2])
            local schemaLen = tonumber(ARGV[3])

            local function read32be(value, offset)
              local b = {string.byte(value, offset + 1, offset + 4)}
              local result = 0
              for i = 1, 4 do result = result * 256 + b[i] end
              return result
            end

            local persisted = 0
            local raw = redis.call('GET', cntKey)
            if raw and string.len(raw) == schemaLen * fieldSize then
              persisted = read32be(raw, idx * fieldSize)
            end
            local pending = tonumber(redis.call('HGET', aggKey, tostring(idx)) or '0')
            local effective = persisted + pending
            if effective < 0 then return 0 end
            return effective
            """;
}
