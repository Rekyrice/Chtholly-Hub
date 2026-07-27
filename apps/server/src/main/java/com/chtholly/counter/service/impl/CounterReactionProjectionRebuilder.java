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

/** Replaces one fenced Redis reaction projection from bounded MySQL fact pages. */
@Component
public class CounterReactionProjectionRebuilder {

    private static final int MYSQL_PAGE_SIZE = 500;
    private static final int REDIS_BATCH_SIZE = 500;
    private static final long UINT32_MAX = 0xffff_ffffL;

    private final StringRedisTemplate redis;
    private final CounterReactionMapper reactionMapper;
    private final DefaultRedisScript<Long> beginScript;
    private final DefaultRedisScript<Long> abortScript;
    private final DefaultRedisScript<Long> writeBitmapScript;
    private final DefaultRedisScript<Long> ownedDeleteScript;
    private final DefaultRedisScript<Long> resetIndexScript;
    private final DefaultRedisScript<Long> finishIndexScript;
    private final DefaultRedisScript<Long> publishCompleteScript;
    private final DefaultRedisScript<List> finalizeScript;

    public CounterReactionProjectionRebuilder(
            StringRedisTemplate redis,
            CounterReactionMapper reactionMapper) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.reactionMapper = Objects.requireNonNull(reactionMapper, "reactionMapper");
        this.beginScript = longScript("lua/counter/begin-reaction-rebuild.lua");
        this.abortScript = longScript("lua/counter/abort-reaction-rebuild.lua");
        this.writeBitmapScript =
                longScript("lua/counter/write-reaction-rebuild-bitmap.lua");
        this.ownedDeleteScript =
                longScript("lua/counter/delete-reaction-rebuild-keys.lua");
        this.resetIndexScript =
                longScript("lua/counter/reset-reaction-rebuild-index.lua");
        this.finishIndexScript =
                longScript("lua/counter/finish-reaction-rebuild-index.lua");
        this.publishCompleteScript =
                longScript("lua/counter/publish-reaction-rebuild.lua");
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
     * Hides the current projection before managed MySQL facts can be changed.
     *
     * <p>The caller must hold the entity maintenance lock. A missing marker is already the
     * required state and is therefore successful.</p>
     */
    public void invalidateComplete(String entityType, String entityId) {
        CounterSchema.requirePersistableIdentity(entityType, entityId);
        Boolean deleted = redis.delete(
                CounterKeys.reactionProjectionCompleteKey(entityType, entityId));
        if (deleted == null) {
            throw new IllegalStateException(
                    "Counter reaction projection completeness was not invalidated");
        }
    }

    /**
     * Streams MySQL memberships into fenced live shards and prepares their absolute counters.
     *
     * <p>Reads remain on MySQL fallback while the fence is active because {@link #begin} removed
     * the complete marker. A failed attempt may leave partial shards, but the next attempt deletes
     * them in bounded batches before writing another snapshot. Completeness remains hidden until
     * {@link #publishComplete} runs after the enclosing MySQL transaction commits.</p>
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
        MetricProjection like = rebuildMetric(entityType, entityId, "like", token);
        MetricProjection favorite = rebuildMetric(entityType, entityId, "fav", token);
        return finalizeProjection(
                entityType,
                entityId,
                token,
                nextEpoch,
                like,
                favorite);
    }

    /**
     * Atomically exposes a prepared projection after its MySQL snapshot transaction committed.
     *
     * @param entityType entity type
     * @param entityId entity ID
     * @param token rebuild ownership token
     */
    public void publishComplete(String entityType, String entityId, String token) {
        requireArguments(entityType, entityId, token, 1L);
        Long result = redis.execute(
                publishCompleteScript,
                List.of(
                        CounterKeys.factMaintenanceFenceKey(entityType, entityId),
                        CounterKeys.reactionProjectionCompleteKey(entityType, entityId)),
                token,
                CounterReactionProjectionStore.COMPLETE_VERSION);
        if (!Long.valueOf(1L).equals(result)) {
            throw new IllegalStateException(
                    "Counter reaction rebuilt projection was not published");
        }
    }

    /**
     * Invalidates a failed owned rebuild and releases only its own fence.
     *
     * <p>If another owner has replaced the token, neither its complete marker nor its fence is
     * changed.</p>
     */
    public void abort(String entityType, String entityId, String token) {
        CounterSchema.requirePersistableIdentity(entityType, entityId);
        Objects.requireNonNull(token, "token");
        Long result = redis.execute(
                abortScript,
                List.of(
                        CounterKeys.factMaintenanceFenceKey(entityType, entityId),
                        CounterKeys.reactionProjectionCompleteKey(entityType, entityId)),
                token);
        if (result == null || (result != 0L && result != 1L)) {
            throw new IllegalStateException("Counter reaction rebuild abort failed");
        }
    }

    private MetricProjection rebuildMetric(
            String entityType,
            String entityId,
            String metric,
            String token) {
        List<Long> page = reactionMapper.listUserIdsAfter(
                entityType, entityId, metric, 0L, MYSQL_PAGE_SIZE);
        validatePage(page, 0L);
        deleteLiveBitmapKeys(entityType, entityId, metric, token);
        resetIndex(entityType, entityId, metric, token);

        long afterUserId = 0L;
        long relationCount = 0L;
        long shardCount = 0L;
        long lastChunk = -1L;
        while (true) {
            if (page.isEmpty()) {
                break;
            }
            relationCount = Math.addExact(relationCount, page.size());
            if (relationCount > UINT32_MAX) {
                throw new IllegalStateException("Counter reaction count exceeds unsigned Int32");
            }

            LinkedHashMap<Long, List<Long>> offsetsByChunk = new LinkedHashMap<>();
            for (Long userId : page) {
                long chunk = BitmapShard.chunkOf(userId);
                if (chunk != lastChunk) {
                    shardCount = Math.addExact(shardCount, 1L);
                    lastChunk = chunk;
                }
                offsetsByChunk.computeIfAbsent(chunk, ignored -> new ArrayList<>())
                        .add(BitmapShard.bitOf(userId));
            }
            writePage(entityType, entityId, metric, token, offsetsByChunk);
            afterUserId = page.get(page.size() - 1);
            if (page.size() < MYSQL_PAGE_SIZE) {
                break;
            }
            page = reactionMapper.listUserIdsAfter(
                    entityType, entityId, metric, afterUserId, MYSQL_PAGE_SIZE);
            validatePage(page, afterUserId);
        }
        finishIndex(
                entityType,
                entityId,
                metric,
                token,
                shardCount);
        return new MetricProjection(relationCount, shardCount);
    }

    private void deleteLiveBitmapKeys(
            String entityType,
            String entityId,
            String metric,
            String token) {
        String prefix = bitmapPrefix(metric, entityType, entityId);
        ScanOptions options = ScanOptions.scanOptions()
                .match(prefix + "*")
                .count(REDIS_BATCH_SIZE)
                .build();
        while (scanAndDeleteLiveBitmapKeys(
                entityType, entityId, metric, token, prefix, options)) {
            // Restarting SCAN after each destructive pass closes cursor-resize gaps.
        }
    }

    private boolean scanAndDeleteLiveBitmapKeys(
            String entityType,
            String entityId,
            String metric,
            String token,
            String prefix,
            ScanOptions options) {
        LinkedHashSet<String> batch = new LinkedHashSet<>(REDIS_BATCH_SIZE);
        boolean found = false;
        try (Cursor<String> cursor = redis.scan(options)) {
            if (cursor == null) {
                throw new IllegalStateException("Counter reaction Bitmap scan returned no cursor");
            }
            while (cursor.hasNext()) {
                String key = cursor.next();
                parseAndValidateChunk(key, prefix, metric, entityType, entityId);
                found = true;
                batch.add(key);
                if (batch.size() == REDIS_BATCH_SIZE) {
                    deleteOwnedInBatches(entityType, entityId, token, batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            deleteOwnedInBatches(entityType, entityId, token, batch);
        }
        return found;
    }

    private void resetIndex(
            String entityType,
            String entityId,
            String metric,
            String token) {
        Long result = redis.execute(
                resetIndexScript,
                List.of(
                        CounterKeys.factMaintenanceFenceKey(entityType, entityId),
                        CounterKeys.bitmapShardIndexKey(metric, entityType, entityId),
                        CounterKeys.bitmapShardIndexCountKey(metric, entityType, entityId)),
                token,
                CounterReactionProjectionStore.SHARD_INDEX_SENTINEL);
        if (!Long.valueOf(1L).equals(result)) {
            throw new IllegalStateException("Counter reaction shard index reset failed");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void writePage(
            String entityType,
            String entityId,
            String metric,
            String token,
            Map<Long, List<Long>> offsetsByChunk) {
        List<Object> results = redis.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(RedisOperations operations) {
                for (Map.Entry<Long, List<Long>> entry : offsetsByChunk.entrySet()) {
                    List<String> arguments = new ArrayList<>(entry.getValue().size() + 1);
                    arguments.add(token);
                    entry.getValue().forEach(offset -> arguments.add(Long.toString(offset)));
                    operations.execute(
                            writeBitmapScript,
                            List.of(
                                    CounterKeys.factMaintenanceFenceKey(entityType, entityId),
                                    CounterKeys.bitmapKey(
                                            metric,
                                            entityType,
                                            entityId,
                                            entry.getKey()),
                                    CounterKeys.bitmapShardIndexKey(
                                            metric,
                                            entityType,
                                            entityId)),
                            arguments.toArray(String[]::new));
                }
                return null;
            }
        });
        if (results.size() != offsetsByChunk.size()) {
            throw new IllegalStateException("Counter reaction rebuild write returned an incomplete batch");
        }
        int index = 0;
        for (List<Long> offsets : offsetsByChunk.values()) {
            Object value = results.get(index++);
            if (!(value instanceof Number number)
                    || number.longValue() != offsets.size()) {
                throw new IllegalStateException(
                        "Counter reaction rebuild write returned an invalid result");
            }
        }
    }

    private void finishIndex(
            String entityType,
            String entityId,
            String metric,
            String token,
            long expectedShardCount) {
        Long result = redis.execute(
                finishIndexScript,
                List.of(
                        CounterKeys.factMaintenanceFenceKey(entityType, entityId),
                        CounterKeys.bitmapShardIndexKey(metric, entityType, entityId),
                        CounterKeys.bitmapShardIndexCountKey(metric, entityType, entityId)),
                token,
                Long.toString(expectedShardCount),
                CounterReactionProjectionStore.SHARD_INDEX_SENTINEL);
        if (result == null || result != expectedShardCount) {
            throw new IllegalStateException(
                    "Counter reaction shard index finalization failed");
        }
    }

    private RebuildResult finalizeProjection(
            String entityType,
            String entityId,
            String token,
            long nextEpoch,
            MetricProjection like,
            MetricProjection favorite) {
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
                Long.toString(like.relationCount()),
                Long.toString(favorite.relationCount()),
                Long.toString(nextEpoch),
                CounterReactionProjectionStore.SHARD_INDEX_SENTINEL,
                Long.toString(like.shardCount()),
                Long.toString(favorite.shardCount()));
        if (raw == null
                || raw.size() != 3
                || !(raw.get(0) instanceof Number actualLike)
                || !(raw.get(1) instanceof Number actualFavorite)
                || !(raw.get(2) instanceof Number actualEpoch)
                || actualLike.longValue() != like.relationCount()
                || actualFavorite.longValue() != favorite.relationCount()
                || actualEpoch.longValue() != nextEpoch) {
            throw new IllegalStateException("Counter reaction finalization returned an invalid result");
        }
        return new RebuildResult(like.relationCount(), favorite.relationCount(), nextEpoch);
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

    private record MetricProjection(long relationCount, long shardCount) {}
}
