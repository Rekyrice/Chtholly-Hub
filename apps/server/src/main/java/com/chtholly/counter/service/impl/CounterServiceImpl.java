package com.chtholly.counter.service.impl;

import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.mapper.CounterReactionMapper;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.service.CounterReactionCommandService;
import com.chtholly.counter.service.CounterService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Counter command facade and Redis-backed online read service.
 */
@Service
public class CounterServiceImpl implements CounterService {

    private static final int MYSQL_MEMBERSHIP_BATCH_SIZE = 500;
    private static final int MYSQL_COUNT_BATCH_SIZE = 500;
    private static final long UINT32_MAX = 0xffff_ffffL;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> effectiveCountScript;
    private final DefaultRedisScript<List> reactionCountsScript;
    private final CounterReactionCommandService reactionCommandService;
    private final CounterReactionMapper reactionMapper;
    private final CounterReactionProjectionStore reactionProjectionStore;

    public CounterServiceImpl(StringRedisTemplate redis,
                              CounterReactionCommandService reactionCommandService,
                              CounterReactionMapper reactionMapper,
                              CounterReactionProjectionStore reactionProjectionStore) {
        this.redis = redis;
        this.reactionCommandService = reactionCommandService;
        this.reactionMapper = reactionMapper;
        this.reactionProjectionStore = reactionProjectionStore;
        this.effectiveCountScript = new DefaultRedisScript<>();
        this.effectiveCountScript.setResultType(Long.class);
        this.effectiveCountScript.setScriptText(EFFECTIVE_COUNT_LUA);
        this.reactionCountsScript = new DefaultRedisScript<>();
        this.reactionCountsScript.setResultType(List.class);
        this.reactionCountsScript.setLocation(
                new ClassPathResource("lua/counter/read-reaction-counts.lua"));
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
     * Returns aggregated counts from SDS and falls back to one grouped MySQL fact query when the
     * reaction projection is incomplete.
     *
     * @param metrics Subset of metrics to read (e.g. "like", "fav").
     */
    @Override
    public Map<String, Long> getCounts(String entityType, String entityId, List<String> metrics) {
        CounterSchema.requirePersistableIdentity(entityType, entityId);
        boolean needsReaction = metrics.stream()
                .anyMatch(CounterServiceImpl::isReactionMetric);
        if (!needsReaction) {
            return getNonReactionCounts(entityType, entityId, metrics);
        }

        Map<String, Long> result = new LinkedHashMap<>();
        CountProjectionRead projection = readReactionCounts(entityType, entityId);
        if (!projection.hasCounts()) {
            if (metrics.contains("view")) {
                result.put("view", getEffectiveCount(entityType, entityId, "view"));
            }
        }
        Map<String, Long> reactionFallback = projection.complete()
                ? Map.of()
                : readMysqlReactionCountsBatch(entityType, List.of(entityId))
                        .getOrDefault(entityId, Map.of());

        for (String metric : metrics) {
            if (result.containsKey(metric)) { continue; }
            if (isReactionMetric(metric) && !projection.complete()) {
                result.put(metric, reactionFallback.getOrDefault(metric, 0L));
                continue;
            }
            Integer index = CounterSchema.NAME_TO_IDX.get(metric);
            if (index != null && projection.hasCounts()) {
                result.put(metric, projection.counts()[index]);
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
     * reaction SDS 或完整标记不可用时按实体从 MySQL 关系事实聚合。
     * @param entityType 实体类型
     * @param entityIds 实体ID列表
     * @param metrics 指标名列表
     * @return 每个实体的指标计数映射
     */
    @Override
    public Map<String, Map<String, Long>> getCountsBatch(
            String entityType,
            List<String> entityIds,
            List<String> metrics) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        if (entityIds == null || entityIds.isEmpty()
                || metrics == null || metrics.isEmpty()) {
            return out;
        }

        List<String> distinctEntityIds = entityIds.stream().distinct().toList();
        for (String entityId : distinctEntityIds) {
            CounterSchema.requirePersistableIdentity(entityType, entityId);
        }
        boolean needsReaction = metrics.stream()
                .anyMatch(CounterServiceImpl::isReactionMetric);
        List<CountProjectionRead> projections = needsReaction
                ? readReactionCountsBatch(entityType, distinctEntityIds)
                : readRawCountsBatch(entityType, distinctEntityIds);
        List<String> reactionFallbackIds = new ArrayList<>();
        List<String> missingViewIds = new ArrayList<>();
        for (int index = 0; index < distinctEntityIds.size(); index++) {
            String entityId = distinctEntityIds.get(index);
            CountProjectionRead projection = projections.get(index);
            Map<String, Long> counts = new LinkedHashMap<>();
            if (projection.hasCounts()) {
                for (String metric : metrics) {
                    if (isReactionMetric(metric) && !projection.complete()) {
                        continue;
                    }
                    Integer metricIndex = CounterSchema.NAME_TO_IDX.get(metric);
                    if (metricIndex != null) {
                        counts.put(metric, projection.counts()[metricIndex]);
                    }
                }
            } else if (metrics.contains("view")) {
                missingViewIds.add(entityId);
            }
            if (needsReaction && !projection.complete()) {
                reactionFallbackIds.add(entityId);
            }
            out.put(entityId, counts);
        }

        readEffectiveViewCountsBatch(entityType, missingViewIds)
                .forEach((entityId, count) ->
                        out.get(entityId).put("view", count));

        Map<String, Map<String, Long>> mysqlCounts =
                readMysqlReactionCountsBatch(entityType, reactionFallbackIds);
        for (String entityId : reactionFallbackIds) {
            Map<String, Long> counts = out.get(entityId);
            Map<String, Long> fallback =
                    mysqlCounts.getOrDefault(entityId, Map.of());
            for (String metric : metrics) {
                if (isReactionMetric(metric)) {
                    counts.put(metric, fallback.getOrDefault(metric, 0L));
                }
            }
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

    private Map<String, Long> getNonReactionCounts(
            String entityType,
            String entityId,
            List<String> metrics) {
        byte[] raw = getRaw(CounterKeys.sdsKey(entityType, entityId));
        Map<String, Long> result = new LinkedHashMap<>();
        if (raw == null
                || raw.length != CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE) {
            if (metrics.contains("view")) {
                result.put("view", getEffectiveCount(entityType, entityId, "view"));
            }
            return result;
        }
        long[] counts = decodeSds(raw);
        for (String metric : metrics) {
            Integer index = CounterSchema.NAME_TO_IDX.get(metric);
            if (index != null) {
                result.put(metric, counts[index]);
            }
        }
        return result;
    }

    /** Reads SDS and its complete marker at one Redis Lua linearization point. */
    private CountProjectionRead readReactionCounts(
            String entityType,
            String entityId) {
        List<?> raw = redis.execute(
                reactionCountsScript,
                List.of(
                        CounterKeys.sdsKey(entityType, entityId),
                        CounterKeys.reactionProjectionCompleteKey(
                                entityType, entityId)),
                CounterReactionProjectionStore.COMPLETE_VERSION,
                Integer.toString(CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE),
                Integer.toString(CounterSchema.FIELD_SIZE),
                Integer.toString(CounterSchema.SCHEMA_LEN));
        return mapCountProjection(raw);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<CountProjectionRead> readReactionCountsBatch(
            String entityType,
            List<String> entityIds) {
        List<Object> raw = redis.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(RedisOperations operations) {
                for (String entityId : entityIds) {
                    operations.execute(
                            reactionCountsScript,
                            List.of(
                                    CounterKeys.sdsKey(entityType, entityId),
                                    CounterKeys.reactionProjectionCompleteKey(
                                            entityType, entityId)),
                            CounterReactionProjectionStore.COMPLETE_VERSION,
                            Integer.toString(
                                    CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE),
                            Integer.toString(CounterSchema.FIELD_SIZE),
                            Integer.toString(CounterSchema.SCHEMA_LEN));
                }
                return null;
            }
        });
        if (raw == null || raw.size() != entityIds.size()) {
            throw new IllegalStateException(
                    "Counter reaction count projection returned an incomplete batch");
        }
        return raw.stream()
                .map(CounterServiceImpl::mapCountProjection)
                .toList();
    }

    private List<CountProjectionRead> readRawCountsBatch(
            String entityType,
            List<String> entityIds) {
        List<Object> raw = redis.executePipelined(
                (RedisCallback<Object>) connection -> {
                    for (String entityId : entityIds) {
                        connection.stringCommands().get(
                                CounterKeys.sdsKey(entityType, entityId)
                                        .getBytes(StandardCharsets.UTF_8));
                    }
                    return null;
                },
                RedisSerializer.byteArray());
        if (raw == null || raw.size() != entityIds.size()) {
            throw new IllegalStateException(
                    "Counter count projection returned an incomplete batch");
        }
        int expectedLength = CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE;
        List<CountProjectionRead> result = new ArrayList<>(raw.size());
        for (Object value : raw) {
            if (value instanceof byte[] bytes && bytes.length == expectedLength) {
                result.add(new CountProjectionRead(true, decodeSds(bytes)));
            } else {
                result.add(new CountProjectionRead(false, null));
            }
        }
        return List.copyOf(result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Long> readEffectiveViewCountsBatch(
            String entityType,
            List<String> entityIds) {
        if (entityIds.isEmpty()) {
            return Map.of();
        }
        List<Object> raw = redis.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(RedisOperations operations) {
                for (String entityId : entityIds) {
                    operations.execute(
                            effectiveCountScript,
                            List.of(
                                    CounterKeys.sdsKey(entityType, entityId),
                                    CounterKeys.aggKey(entityType, entityId)),
                            Integer.toString(CounterSchema.IDX_VIEW),
                            Integer.toString(CounterSchema.FIELD_SIZE),
                            Integer.toString(CounterSchema.SCHEMA_LEN));
                }
                return null;
            }
        });
        if (raw == null || raw.size() != entityIds.size()) {
            throw new IllegalStateException(
                    "Counter effective view projection returned an incomplete batch");
        }
        Map<String, Long> result = new LinkedHashMap<>();
        for (int index = 0; index < entityIds.size(); index++) {
            Object value = raw.get(index);
            if (!(value instanceof Number number)) {
                throw new IllegalStateException(
                        "Counter effective view projection returned an invalid result");
            }
            result.put(entityIds.get(index), Math.max(0L, number.longValue()));
        }
        return Map.copyOf(result);
    }

    private Map<String, Map<String, Long>> readMysqlReactionCountsBatch(
            String entityType,
            List<String> entityIds) {
        if (entityIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        for (int from = 0; from < entityIds.size(); from += MYSQL_COUNT_BATCH_SIZE) {
            List<String> chunk = entityIds.subList(
                    from, Math.min(from + MYSQL_COUNT_BATCH_SIZE, entityIds.size()));
            List<CounterReactionMapper.ReactionCountRow> rows =
                    reactionMapper.countByEntityMetrics(entityType, chunk);
            if (rows == null) {
                throw new IllegalStateException(
                        "Counter reaction MySQL count fallback returned no result");
            }
            Set<String> requested = new HashSet<>(chunk);
            for (CounterReactionMapper.ReactionCountRow row : rows) {
                if (row == null
                        || !requested.contains(row.entityId())
                        || !isReactionMetric(row.metric())
                        || row.countValue() < 0L) {
                    throw new IllegalStateException(
                            "Counter reaction MySQL count fallback returned an unexpected row");
                }
                Map<String, Long> entityCounts =
                        result.computeIfAbsent(row.entityId(), ignored -> new LinkedHashMap<>());
                if (entityCounts.putIfAbsent(row.metric(), row.countValue()) != null) {
                    throw new IllegalStateException(
                            "Counter reaction MySQL count fallback returned a duplicate row");
                }
            }
        }
        return result;
    }

    /** Reads SDS raw bytes for non-reaction counters. */
    private byte[] getRaw(String key) {
        return redis.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean isReactionMetric(String metric) {
        return "like".equals(metric) || "fav".equals(metric);
    }

    private static CountProjectionRead mapCountProjection(Object raw) {
        if (!(raw instanceof List<?> values)
                || values.isEmpty()
                || !(values.getFirst() instanceof Number statusNumber)) {
            throw new IllegalStateException(
                    "Counter reaction count projection returned an invalid result");
        }
        long status = statusNumber.longValue();
        if (status == -1L && values.size() == 1) {
            return new CountProjectionRead(false, null);
        }
        if ((status != 0L && status != 1L)
                || values.size() != CounterSchema.SCHEMA_LEN + 1) {
            throw new IllegalStateException(
                    "Counter reaction count projection returned an invalid result");
        }
        long[] counts = new long[CounterSchema.SCHEMA_LEN];
        for (int index = 0; index < counts.length; index++) {
            Object value = values.get(index + 1);
            if (!(value instanceof Number number)
                    || number.longValue() < 0L
                    || number.longValue() > UINT32_MAX) {
                throw new IllegalStateException(
                        "Counter reaction count projection returned an invalid count");
            }
            counts[index] = number.longValue();
        }
        return new CountProjectionRead(status == 1L, counts);
    }

    private static long[] decodeSds(byte[] raw) {
        long[] counts = new long[CounterSchema.SCHEMA_LEN];
        for (int index = 0; index < counts.length; index++) {
            counts[index] = readInt32BE(
                    raw, index * CounterSchema.FIELD_SIZE);
        }
        return counts;
    }

    private record CountProjectionRead(boolean complete, long[] counts) {
        private boolean hasCounts() {
            return counts != null;
        }
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
