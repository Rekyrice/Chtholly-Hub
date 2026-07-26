package com.chtholly.counter.service.impl;

import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Maintains and reads the rebuildable Redis reaction projection. */
@Component
public class CounterReactionProjectionStore {

    public static final String COMPLETE_VERSION = "@mysql-v1";
    public static final String SHARD_INDEX_SENTINEL = "@mysql-v1";

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> projectScript;
    private final DefaultRedisScript<Long> readScript;

    public CounterReactionProjectionStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.projectScript = new DefaultRedisScript<>();
        this.projectScript.setResultType(List.class);
        this.projectScript.setLocation(
                new ClassPathResource("lua/counter/project-reaction-state.lua"));
        this.readScript = new DefaultRedisScript<>();
        this.readScript.setResultType(Long.class);
        this.readScript.setLocation(
                new ClassPathResource("lua/counter/read-reaction-state.lua"));
    }

    /**
     * Projects current MySQL states in bounded Redis pipelines.
     *
     * <p>The Lua script atomically preserves or removes the existing complete marker. Online
     * projection never publishes completeness; only a full MySQL rebuild may do that. If a
     * maintenance fence is active, the script dirties that fence and defers the bit mutation so
     * the stale rebuild cannot become visible.</p>
     *
     * @param targetStates current authoritative state by relation key
     */
    public void project(Map<CounterReactionKey, Boolean> targetStates) {
        if (targetStates == null || targetStates.isEmpty()) {
            return;
        }
        LinkedHashMap<CounterReactionKey, Boolean> targets = new LinkedHashMap<>();
        targetStates.forEach((key, value) ->
                targets.put(Objects.requireNonNull(key, "reaction key"), Boolean.TRUE.equals(value)));

        List<Object> results = executeProjectionPipeline(targets);
        if (results.size() != targets.size()) {
            throw new IllegalStateException("Counter reaction projection returned an incomplete batch");
        }

        int index = 0;
        Set<CounterReactionKey> failedKeys = new LinkedHashSet<>();
        for (CounterReactionKey key : targets.keySet()) {
            ProjectionResult result = mapProjectionResult(results.get(index++));
            if (result.status() < 0L) {
                failedKeys.add(key);
            }
        }
        if (!failedKeys.isEmpty()) {
            throw new ProjectionBatchException(failedKeys);
        }
    }

    /**
     * Reads a bounded relation batch, returning empty optionals for structurally incomplete entities.
     *
     * @param keys relation keys
     * @return one result per distinct input key
     */
    public Map<CounterReactionKey, Optional<Boolean>> readBatch(List<CounterReactionKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        List<CounterReactionKey> distinct = keys.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<Object> raw = redis.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public Object execute(RedisOperations operations) {
                for (CounterReactionKey key : distinct) {
                    operations.execute(
                            readScript,
                            readKeys(key),
                            COMPLETE_VERSION,
                            SHARD_INDEX_SENTINEL,
                            Long.toString(BitmapShard.bitOf(key.userId())));
                }
                return null;
            }
        });
        if (raw.size() != distinct.size()) {
            throw new IllegalStateException("Counter reaction read projection returned an incomplete batch");
        }
        Map<CounterReactionKey, Optional<Boolean>> result = new LinkedHashMap<>();
        for (int index = 0; index < distinct.size(); index++) {
            Object value = raw.get(index);
            if (!(value instanceof Number number)
                    || (number.longValue() != -1L
                    && number.longValue() != 0L
                    && number.longValue() != 1L)) {
                throw new IllegalStateException("Counter reaction read projection returned an invalid state");
            }
            result.put(distinct.get(index), number.longValue() < 0L
                    ? Optional.empty()
                    : Optional.of(number.longValue() == 1L));
        }
        return Map.copyOf(result);
    }

    /** Reads one relation from the projection when it is structurally complete. */
    public Optional<Boolean> read(CounterReactionKey key) {
        return readBatch(List.of(Objects.requireNonNull(key, "key"))).get(key);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Object> executeProjectionPipeline(Map<CounterReactionKey, Boolean> targets) {
        return redis.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(RedisOperations operations) {
                for (Map.Entry<CounterReactionKey, Boolean> entry : targets.entrySet()) {
                    CounterReactionKey key = entry.getKey();
                    operations.execute(
                            projectScript,
                            projectKeys(key),
                            Long.toString(BitmapShard.bitOf(key.userId())),
                            entry.getValue() ? "1" : "0",
                            Integer.toString(CounterSchema.NAME_TO_IDX.get(key.metric())),
                            Integer.toString(CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE),
                            Integer.toString(CounterSchema.FIELD_SIZE),
                            SHARD_INDEX_SENTINEL,
                            COMPLETE_VERSION);
                }
                return null;
            }
        });
    }

    private static List<String> projectKeys(CounterReactionKey key) {
        return List.of(
                CounterKeys.bitmapKey(
                        key.metric(), key.entityType(), key.entityId(),
                        BitmapShard.chunkOf(key.userId())),
                CounterKeys.sdsKey(key.entityType(), key.entityId()),
                CounterKeys.factMaintenanceFenceKey(key.entityType(), key.entityId()),
                CounterKeys.bitmapShardIndexKey(key.metric(), key.entityType(), key.entityId()),
                CounterKeys.bitmapShardIndexCountKey(key.metric(), key.entityType(), key.entityId()),
                CounterKeys.reactionProjectionCompleteKey(key.entityType(), key.entityId()));
    }

    private static List<String> readKeys(CounterReactionKey key) {
        return List.of(
                CounterKeys.reactionProjectionCompleteKey(key.entityType(), key.entityId()),
                CounterKeys.bitmapShardIndexKey(key.metric(), key.entityType(), key.entityId()),
                CounterKeys.bitmapShardIndexCountKey(key.metric(), key.entityType(), key.entityId()),
                CounterKeys.bitmapKey(
                        key.metric(), key.entityType(), key.entityId(),
                        BitmapShard.chunkOf(key.userId())));
    }

    private static ProjectionResult mapProjectionResult(Object value) {
        if (!(value instanceof List<?> values) || values.size() != 2
                || !(values.get(0) instanceof Number status)
                || !(values.get(1) instanceof Number delta)
                || (status.longValue() != -2L && status.longValue() != -1L
                && status.longValue() != 0L
                && status.longValue() != 1L && status.longValue() != 2L)
                || (delta.longValue() < -1L || delta.longValue() > 1L)) {
            throw new IllegalStateException("Counter reaction projection returned an invalid result");
        }
        return new ProjectionResult(status.longValue(), delta.longValue());
    }

    /** Identifies relation keys that a completed Redis pipeline could not project. */
    public static final class ProjectionBatchException extends IllegalStateException {

        private final Set<CounterReactionKey> failedKeys;

        public ProjectionBatchException(Set<CounterReactionKey> failedKeys) {
            super(failureMessage(failedKeys));
            this.failedKeys = Collections.unmodifiableSet(
                    new LinkedHashSet<>(failedKeys));
        }

        public Set<CounterReactionKey> failedKeys() {
            return failedKeys;
        }

        private static String failureMessage(
                Set<CounterReactionKey> failedKeys) {
            Objects.requireNonNull(failedKeys, "failedKeys");
            if (failedKeys.isEmpty()
                    || failedKeys.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "Counter reaction projection failure keys are required");
            }
            List<String> identities = failedKeys.stream()
                    .map(key -> key.entityType()
                            + ":" + key.entityId()
                            + ":" + key.metric()
                            + ":" + key.userId())
                    .toList();
            return "Counter reaction projection failed for relation keys ["
                    + String.join(", ", identities)
                    + "]";
        }
    }

    private record ProjectionResult(long status, long delta) {}
}
