package com.chtholly.counter.service.impl;

import com.chtholly.counter.mapper.CounterReactionMapper;
import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Replaces one fenced Redis reaction projection with a bounded MySQL fact snapshot. */
@Component
public class CounterReactionProjectionRebuilder {

    private static final int MYSQL_PAGE_SIZE = 500;
    private static final int REDIS_BATCH_SIZE = 500;
    private static final int REDIS_RENAME_PAIR_BATCH_SIZE = 250;
    private static final long STAGE_TTL_SECONDS = 3_600L;
    private static final long UINT32_MAX = 0xffff_ffffL;

    private final StringRedisTemplate redis;
    private final CounterReactionMapper reactionMapper;
    private final DefaultRedisScript<Long> beginScript;
    private final DefaultRedisScript<Long> releaseFenceScript;
    private final DefaultRedisScript<Long> stageScript;
    private final DefaultRedisScript<Long> ownedDeleteScript;
    private final DefaultRedisScript<Long> ownedRenameScript;
    private final DefaultRedisScript<Long> ownedIndexAddScript;
    private final DefaultRedisScript<Long> ownedIndexFinishScript;
    private final DefaultRedisScript<List> finalizeScript;

    public CounterReactionProjectionRebuilder(
            StringRedisTemplate redis,
            CounterReactionMapper reactionMapper) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.reactionMapper = Objects.requireNonNull(reactionMapper, "reactionMapper");
        this.beginScript = longScript("lua/counter/begin-reaction-rebuild.lua");
        this.releaseFenceScript = longScript(
                "lua/counter/fact-maintenance-fence-release.lua");
        this.stageScript = longScript("lua/counter/stage-reaction-bitmap.lua");
        this.ownedDeleteScript = longScript(
                "lua/counter/delete-reaction-rebuild-keys.lua");
        this.ownedRenameScript = longScript(
                "lua/counter/rename-reaction-rebuild-shards.lua");
        this.ownedIndexAddScript = longScript(
                "lua/counter/add-reaction-rebuild-index.lua");
        this.ownedIndexFinishScript = longScript(
                "lua/counter/finish-reaction-rebuild-index.lua");
        this.finalizeScript = new DefaultRedisScript<>();
        this.finalizeScript.setResultType(List.class);
        this.finalizeScript.setLocation(
                new ClassPathResource("lua/counter/finalize-reaction-rebuild.lua"));
    }

    /**
     * Installs the maintenance fence and invalidates completeness in one Redis script.
     *
     * @param entityType entity type
     * @param entityId entity ID
     * @param token rebuild ownership token
     */
    public void begin(String entityType, String entityId, String token) {
        requireArguments(entityType, entityId, token, 1L);
        Long result = redis.execute(
                beginScript,
                List.of(
                        CounterKeys.factMaintenanceFenceKey(entityType, entityId),
                        CounterKeys.reactionProjectionCompleteKey(entityType, entityId)),
                token);
        if (!Long.valueOf(1L).equals(result)) {
            throw new IllegalStateException("Counter reaction rebuild fence was not installed");
        }
    }

    /**
     * Pages MySQL memberships, replaces all Bitmap shards, and atomically publishes completeness.
     *
     * @param entityType entity type
     * @param entityId entity ID
     * @param token rebuild ownership token
     * @param nextEpoch epoch allocated from locked MySQL snapshot rows
     * @return absolute projection counts and epoch
     */
    public RebuildResult rebuild(
            String entityType,
            String entityId,
            String token,
            long nextEpoch) {
        requireArguments(entityType, entityId, token, nextEpoch);
        Set<String> temporaryKeys = new LinkedHashSet<>();
        RuntimeException primaryFailure = null;
        try {
            MetricStage like = stageMetric(entityType, entityId, "like", token, temporaryKeys);
            MetricStage favorite = stageMetric(entityType, entityId, "fav", token, temporaryKeys);
            replaceLiveProjection(entityType, entityId, token, List.of(like, favorite));
            return finalizeProjection(
                    entityType, entityId, token, nextEpoch, like.count(), favorite.count());
        } catch (RuntimeException exception) {
            primaryFailure = exception;
            throw exception;
        } finally {
            try {
                deleteInBatches(temporaryKeys);
            } catch (RuntimeException cleanupFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    /**
     * Invalidates a failed rebuild and releases its fence without deleting another owner's token.
     *
     * @param entityType entity type
     * @param entityId entity ID
     * @param token rebuild ownership token
     */
    public void abort(String entityType, String entityId, String token) {
        CounterSchema.requirePersistableIdentity(entityType, entityId);
        Objects.requireNonNull(token, "token");
        RuntimeException failure = null;
        try {
            redis.delete(CounterKeys.reactionProjectionCompleteKey(entityType, entityId));
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            Long released = redis.execute(
                    releaseFenceScript,
                    List.of(CounterKeys.factMaintenanceFenceKey(entityType, entityId)),
                    token);
            if (released == null || (released != 0L && released != 1L)) {
                throw new IllegalStateException("Counter reaction rebuild fence release failed");
            }
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private MetricStage stageMetric(
            String entityType,
            String entityId,
            String metric,
            String token,
            Set<String> temporaryKeys) {
        LinkedHashMap<Long, String> stageKeys = new LinkedHashMap<>();
        long afterUserId = 0L;
        long count = 0L;
        while (true) {
            List<Long> page = reactionMapper.listUserIdsAfter(
                    entityType, entityId, metric, afterUserId, MYSQL_PAGE_SIZE);
            validatePage(page, afterUserId);
            if (page.isEmpty()) {
                break;
            }
            count = Math.addExact(count, page.size());
            if (count > UINT32_MAX) {
                throw new IllegalStateException("Counter reaction count exceeds unsigned Int32");
            }

            LinkedHashMap<Long, List<Long>> offsetsByChunk = new LinkedHashMap<>();
            for (Long userId : page) {
                long chunk = BitmapShard.chunkOf(userId);
                offsetsByChunk.computeIfAbsent(chunk, ignored -> new ArrayList<>())
                        .add(BitmapShard.bitOf(userId));
                stageKeys.computeIfAbsent(
                        chunk,
                        ignored -> CounterKeys.reactionProjectionStageBitmapKey(
                                token, metric, entityType, entityId, chunk));
            }
            temporaryKeys.addAll(stageKeys.values());
            stagePage(entityType, entityId, token, stageKeys, offsetsByChunk);
            afterUserId = page.get(page.size() - 1);
            if (page.size() < MYSQL_PAGE_SIZE) {
                break;
            }
        }
        return new MetricStage(metric, count, stageKeys);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stagePage(
            String entityType,
            String entityId,
            String token,
            Map<Long, String> stageKeys,
            Map<Long, List<Long>> offsetsByChunk) {
        List<Object> results = redis.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(RedisOperations operations) {
                for (Map.Entry<Long, List<Long>> entry : offsetsByChunk.entrySet()) {
                    List<String> arguments = new ArrayList<>(entry.getValue().size() + 2);
                    arguments.add(token);
                    arguments.add(Long.toString(STAGE_TTL_SECONDS));
                    entry.getValue().forEach(offset -> arguments.add(Long.toString(offset)));
                    operations.execute(
                            stageScript,
                            List.of(
                                    stageKeys.get(entry.getKey()),
                                    CounterKeys.factMaintenanceFenceKey(entityType, entityId)),
                            arguments.toArray(String[]::new));
                }
                return null;
            }
        });
        if (results.size() != offsetsByChunk.size()) {
            throw new IllegalStateException("Counter reaction staging returned an incomplete batch");
        }
        int index = 0;
        for (List<Long> offsets : offsetsByChunk.values()) {
            Object value = results.get(index++);
            if (!(value instanceof Number number)
                    || number.longValue() != offsets.size()) {
                throw new IllegalStateException("Counter reaction staging returned an invalid result");
            }
        }
    }

    private void replaceLiveProjection(
            String entityType,
            String entityId,
            String token,
            List<MetricStage> stages) {
        for (MetricStage stage : stages) {
            LinkedHashSet<String> deleteKeys = new LinkedHashSet<>(
                    scanLiveBitmapKeys(stage.metric(), entityType, entityId));
            deleteKeys.add(CounterKeys.bitmapShardIndexKey(
                    stage.metric(), entityType, entityId));
            deleteKeys.add(CounterKeys.bitmapShardIndexCountKey(
                    stage.metric(), entityType, entityId));
            deleteOwnedInBatches(entityType, entityId, token, deleteKeys);
        }

        List<RenamePair> renames = stages.stream()
                .flatMap(stage -> stage.stageKeys().entrySet().stream()
                        .map(entry -> new RenamePair(
                                entry.getValue(),
                                CounterKeys.bitmapKey(
                                        stage.metric(),
                                        entityType,
                                        entityId,
                                        entry.getKey()))))
                .toList();
        renameOwnedInBatches(entityType, entityId, token, renames);

        for (MetricStage stage : stages) {
            String indexKey = CounterKeys.bitmapShardIndexKey(
                    stage.metric(), entityType, entityId);
            List<String> liveKeys = stage.stageKeys().keySet().stream()
                    .sorted()
                    .map(chunk -> CounterKeys.bitmapKey(
                            stage.metric(), entityType, entityId, chunk))
                    .toList();
            List<String> members = new ArrayList<>(liveKeys.size() + 1);
            members.add(CounterReactionProjectionStore.SHARD_INDEX_SENTINEL);
            members.addAll(liveKeys);
            addIndexMembersOwned(
                    entityType, entityId, token, indexKey, members);
            finishIndexOwned(
                    entityType,
                    entityId,
                    token,
                    indexKey,
                    CounterKeys.bitmapShardIndexCountKey(
                            stage.metric(), entityType, entityId),
                    liveKeys.size());
        }
    }

    private Set<String> scanLiveBitmapKeys(
            String metric,
            String entityType,
            String entityId) {
        String prefix = bitmapPrefix(metric, entityType, entityId);
        ScanOptions options = ScanOptions.scanOptions()
                .match(prefix + "*")
                .count(REDIS_BATCH_SIZE)
                .build();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        try (Cursor<String> cursor = redis.scan(options)) {
            if (cursor == null) {
                throw new IllegalStateException("Counter reaction Bitmap scan returned no cursor");
            }
            while (cursor.hasNext()) {
                String key = cursor.next();
                parseAndValidateChunk(key, prefix, metric, entityType, entityId);
                keys.add(key);
            }
        }
        return keys;
    }

    private void renameOwnedInBatches(
            String entityType,
            String entityId,
            String token,
            List<RenamePair> renames) {
        if (renames.isEmpty()) {
            return;
        }
        for (int from = 0; from < renames.size(); from += REDIS_RENAME_PAIR_BATCH_SIZE) {
            List<RenamePair> batch = renames.subList(
                    from, Math.min(from + REDIS_RENAME_PAIR_BATCH_SIZE, renames.size()));
            List<String> keys = new ArrayList<>(batch.size() * 2 + 1);
            keys.add(CounterKeys.factMaintenanceFenceKey(entityType, entityId));
            for (RenamePair pair : batch) {
                keys.add(pair.source());
                keys.add(pair.target());
            }
            Long renamed = redis.execute(ownedRenameScript, keys, token);
            if (renamed == null || renamed != batch.size()) {
                throw new IllegalStateException(
                        "Counter reaction shard rename returned an invalid result");
            }
        }
    }

    private RebuildResult finalizeProjection(
            String entityType,
            String entityId,
            String token,
            long nextEpoch,
            long likeCount,
            long favCount) {
        List<?> raw = redis.execute(
                finalizeScript,
                List.of(
                        CounterKeys.sdsKey(entityType, entityId),
                        CounterKeys.aggKey(entityType, entityId),
                        CounterKeys.aggIndexKey(),
                        CounterKeys.factMaintenanceFenceKey(entityType, entityId),
                        CounterKeys.factEpochKey(entityType, entityId),
                        CounterKeys.reactionProjectionCompleteKey(entityType, entityId),
                        CounterKeys.bitmapShardIndexKey("like", entityType, entityId),
                        CounterKeys.bitmapShardIndexCountKey("like", entityType, entityId),
                        CounterKeys.bitmapShardIndexKey("fav", entityType, entityId),
                        CounterKeys.bitmapShardIndexCountKey("fav", entityType, entityId)),
                token,
                Integer.toString(CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE),
                Integer.toString(CounterSchema.FIELD_SIZE),
                Integer.toString(CounterSchema.IDX_LIKE),
                Integer.toString(CounterSchema.IDX_FAV),
                Long.toString(likeCount),
                Long.toString(favCount),
                Long.toString(nextEpoch),
                CounterReactionProjectionStore.COMPLETE_VERSION,
                CounterReactionProjectionStore.SHARD_INDEX_SENTINEL,
                bitmapPrefix("like", entityType, entityId),
                bitmapPrefix("fav", entityType, entityId));
        if (raw == null
                || raw.size() != 3
                || !(raw.get(0) instanceof Number actualLike)
                || !(raw.get(1) instanceof Number actualFavorite)
                || !(raw.get(2) instanceof Number actualEpoch)
                || actualLike.longValue() != likeCount
                || actualFavorite.longValue() != favCount
                || actualEpoch.longValue() != nextEpoch) {
            throw new IllegalStateException("Counter reaction finalization returned an invalid result");
        }
        return new RebuildResult(likeCount, favCount, nextEpoch);
    }

    private void deleteInBatches(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        List<String> ordered = keys.stream().filter(Objects::nonNull).distinct().toList();
        for (int from = 0; from < ordered.size(); from += REDIS_BATCH_SIZE) {
            Collection<String> batch = ordered.subList(
                    from, Math.min(from + REDIS_BATCH_SIZE, ordered.size()));
            Long deleted = redis.delete(batch);
            if (deleted == null || deleted < 0L) {
                throw new IllegalStateException("Counter reaction Redis cleanup failed");
            }
        }
    }

    private void deleteOwnedInBatches(
            String entityType,
            String entityId,
            String token,
            Collection<String> keys) {
        List<String> ordered = keys.stream().filter(Objects::nonNull).distinct().toList();
        for (int from = 0; from < ordered.size(); from += REDIS_BATCH_SIZE) {
            List<String> scriptKeys = new ArrayList<>(REDIS_BATCH_SIZE + 1);
            scriptKeys.add(CounterKeys.factMaintenanceFenceKey(entityType, entityId));
            scriptKeys.addAll(ordered.subList(
                    from, Math.min(from + REDIS_BATCH_SIZE, ordered.size())));
            Long deleted = redis.execute(ownedDeleteScript, scriptKeys, token);
            if (deleted == null || deleted < 0L) {
                throw new IllegalStateException(
                        "Counter reaction owned Redis cleanup failed");
            }
        }
    }

    private void addIndexMembersOwned(
            String entityType,
            String entityId,
            String token,
            String indexKey,
            List<String> members) {
        for (int from = 0; from < members.size(); from += REDIS_BATCH_SIZE) {
            List<String> batch = members.subList(
                    from, Math.min(from + REDIS_BATCH_SIZE, members.size()));
            List<String> arguments = new ArrayList<>(batch.size() + 1);
            arguments.add(token);
            arguments.addAll(batch);
            Long added = redis.execute(
                    ownedIndexAddScript,
                    List.of(
                            CounterKeys.factMaintenanceFenceKey(entityType, entityId),
                            indexKey),
                    arguments.toArray(String[]::new));
            if (added == null || added != batch.size()) {
                throw new IllegalStateException(
                        "Counter reaction shard index rebuild failed");
            }
        }
    }

    private void finishIndexOwned(
            String entityType,
            String entityId,
            String token,
            String indexKey,
            String indexCountKey,
            int expectedCount) {
        Long result = redis.execute(
                ownedIndexFinishScript,
                List.of(
                        CounterKeys.factMaintenanceFenceKey(entityType, entityId),
                        indexKey,
                        indexCountKey),
                token,
                Integer.toString(expectedCount),
                CounterReactionProjectionStore.SHARD_INDEX_SENTINEL);
        if (result == null || result != expectedCount) {
            throw new IllegalStateException(
                    "Counter reaction shard index finalization failed");
        }
    }

    private static void validatePage(List<Long> page, long afterUserId) {
        if (page == null) {
            throw new IllegalStateException("Counter reaction MySQL page returned no result");
        }
        if (page.size() > MYSQL_PAGE_SIZE) {
            throw new IllegalStateException("Counter reaction MySQL page exceeded its bound");
        }
        long previous = afterUserId;
        for (Long userId : page) {
            if (userId == null || userId <= previous) {
                throw new IllegalStateException(
                        "Counter reaction MySQL page is not strictly ordered");
            }
            previous = userId;
        }
    }

    private static long parseAndValidateChunk(
            String key,
            String prefix,
            String metric,
            String entityType,
            String entityId) {
        if (key == null || !key.startsWith(prefix)) {
            throw new IllegalStateException("Counter reaction Bitmap scan escaped its entity prefix");
        }
        String rawChunk = key.substring(prefix.length());
        try {
            long chunk = Long.parseLong(rawChunk);
            if (chunk < 0L
                    || !CounterKeys.bitmapKey(metric, entityType, entityId, chunk).equals(key)) {
                throw new IllegalStateException("Counter reaction Bitmap shard key is invalid");
            }
            return chunk;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Counter reaction Bitmap shard key is invalid", exception);
        }
    }

    private static String bitmapPrefix(String metric, String entityType, String entityId) {
        return "bm:" + metric + ":" + entityType + ":" + entityId + ":";
    }

    private static void requireArguments(
            String entityType,
            String entityId,
            String token,
            long nextEpoch) {
        CounterSchema.requirePersistableIdentity(entityType, entityId);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Counter reaction rebuild token must not be blank");
        }
        if (nextEpoch <= 0L) {
            throw new IllegalArgumentException("Counter reaction rebuild epoch must be positive");
        }
    }

    private static DefaultRedisScript<Long> longScript(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setLocation(new ClassPathResource(location));
        return script;
    }

    /** Absolute result published by one full entity rebuild. */
    public record RebuildResult(long likeCount, long favCount, long factEpoch) {}

    private record MetricStage(
            String metric,
            long count,
            LinkedHashMap<Long, String> stageKeys) {}

    private record RenamePair(String source, String target) {}
}
