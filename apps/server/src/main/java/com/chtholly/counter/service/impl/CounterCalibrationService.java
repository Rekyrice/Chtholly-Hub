package com.chtholly.counter.service.impl;

import com.chtholly.counter.mapper.CounterEntityIdentity;
import com.chtholly.counter.mapper.CounterPersistenceMapper;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Restores reaction counts from authoritative Bitmap shards into the fixed Redis counter. */
@Service
public class CounterCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(CounterCalibrationService.class);
    private static final long UINT32_MAX = 0xffff_ffffL;
    private static final int MYSQL_DISCOVERY_WINDOW_MIN = 50;

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final CounterPersistenceMapper persistenceMapper;
    private final CounterBitmapIndexService bitmapIndex;
    private final boolean scheduledEnabled;
    private final int scheduledBatchSize;
    private final AtomicLong mysqlRotation = new AtomicLong();
    private final AtomicLong singleSlotSourceRotation = new AtomicLong();
    private final DefaultRedisScript<Long> acquireFenceScript;
    private final DefaultRedisScript<Long> releaseFenceScript;
    private final DefaultRedisScript<List> reconcileScript;

    public CounterCalibrationService(
            StringRedisTemplate redis,
            RedissonClient redisson,
            CounterPersistenceMapper persistenceMapper,
            CounterBitmapIndexService bitmapIndex,
            @Value("${counter.calibration.enabled:true}") boolean scheduledEnabled,
            @Value("${counter.calibration.batch-size:50}") int scheduledBatchSize) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        this.persistenceMapper = Objects.requireNonNull(persistenceMapper, "persistenceMapper");
        this.bitmapIndex = Objects.requireNonNull(bitmapIndex, "bitmapIndex");
        if (scheduledBatchSize < 1 || scheduledBatchSize > 1_000) {
            throw new IllegalArgumentException("scheduledBatchSize must be between 1 and 1000");
        }
        this.scheduledEnabled = scheduledEnabled;
        this.scheduledBatchSize = scheduledBatchSize;
        this.acquireFenceScript = new DefaultRedisScript<>();
        this.acquireFenceScript.setResultType(Long.class);
        this.acquireFenceScript.setLocation(
                new ClassPathResource("lua/counter/fact-maintenance-fence-acquire.lua"));
        this.releaseFenceScript = new DefaultRedisScript<>();
        this.releaseFenceScript.setResultType(Long.class);
        this.releaseFenceScript.setLocation(
                new ClassPathResource("lua/counter/fact-maintenance-fence-release.lua"));
        this.reconcileScript = new DefaultRedisScript<>();
        this.reconcileScript.setResultType(List.class);
        this.reconcileScript.setLocation(
                new ClassPathResource("lua/counter/reconcile-reaction-counts.lua"));
    }

    /** Reconciles one entity while normal toggles are fenced. */
    public ReconciliationResult reconcileEntity(String entityType, String entityId) {
        requireIdentity(entityType, entityId);
        RLock lock = redisson.getLock(CounterKeys.factMaintenanceLockKey(entityType, entityId));
        boolean locked = false;
        boolean fenced = false;
        String token = UUID.randomUUID().toString();
        try {
            locked = lock.tryLock(0L, TimeUnit.MILLISECONDS);
            if (!locked) {
                throw new IllegalStateException("Counter reconciliation lock is busy");
            }
            Long acquired = redis.execute(
                    acquireFenceScript,
                    List.of(CounterKeys.factMaintenanceFenceKey(entityType, entityId)),
                    token);
            fenced = Long.valueOf(1L).equals(acquired);
            if (!fenced) {
                throw new IllegalStateException("Counter reconciliation fence is busy");
            }

            long likeCount = countBitmapShards("like", entityType, entityId);
            long favCount = countBitmapShards("fav", entityType, entityId);
            List<?> raw = redis.execute(
                    reconcileScript,
                    List.of(
                            CounterKeys.sdsKey(entityType, entityId),
                            CounterKeys.aggKey(entityType, entityId),
                            CounterKeys.aggIndexKey(),
                            CounterKeys.factMaintenanceFenceKey(entityType, entityId),
                            CounterKeys.factEpochKey(entityType, entityId)),
                    token,
                    String.valueOf(CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE),
                    String.valueOf(CounterSchema.FIELD_SIZE),
                    String.valueOf(CounterSchema.IDX_LIKE),
                    String.valueOf(CounterSchema.IDX_FAV),
                    String.valueOf(likeCount),
                    String.valueOf(favCount));
            ReconciliationResult result = mapResult(raw);
            persistenceMapper.replaceReactionSnapshots(
                    entityType, entityId, result.likeCount(), result.favCount(), result.factEpoch());
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reconciling counters", exception);
        } finally {
            try {
                if (fenced) {
                    redis.execute(
                            releaseFenceScript,
                            List.of(CounterKeys.factMaintenanceFenceKey(entityType, entityId)),
                            token);
                }
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        }
    }

    /** Periodically repairs Redis and MySQL from Bitmap authority in bounded entity batches. */
    @Scheduled(fixedDelayString = "${counter.calibration.fixed-delay:PT5M}")
    public void reconcileScheduled() {
        if (!scheduledEnabled) { return; }
        List<CounterEntityIdentity> redisCandidates;
        int redisLimit = scheduledBatchSize == 1 ? 1 : scheduledBatchSize - 1;
        try {
            redisCandidates = bitmapIndex.discoverCandidates(redisLimit);
            if (!bitmapIndex.isBackfillComplete()) { return; }
        } catch (RuntimeException exception) {
            log.warn("Counter calibration Bitmap discovery failed: {}", exception.getMessage());
            return;
        }
        LinkedHashSet<CounterEntityIdentity> candidates = new LinkedHashSet<>();
        addScheduledCandidates(candidates, redisCandidates);
        for (CounterEntityIdentity candidate : candidates) {
            try {
                CounterSchema.requirePersistableIdentity(candidate.entityType(), candidate.entityId());
                reconcileEntity(candidate.entityType(), candidate.entityId());
            } catch (RuntimeException exception) {
                log.warn(
                        "Counter calibration failed entityType={} entityId={}: {}",
                        candidate.entityType(), candidate.entityId(), exception.getMessage());
            } finally {
                try {
                    bitmapIndex.rotateCandidate(candidate);
                } catch (RuntimeException exception) {
                    log.warn(
                            "Counter calibration candidate rotation failed entityType={} entityId={}: {}",
                            candidate.entityType(), candidate.entityId(), exception.getMessage());
                }
            }
        }
    }

    private void addScheduledCandidates(
            Set<CounterEntityIdentity> output,
            List<CounterEntityIdentity> redisCandidates) {
        if (scheduledBatchSize == 1) {
            boolean redisFirst = Math.floorMod(singleSlotSourceRotation.getAndIncrement(), 2L) == 0L;
            if (redisFirst) {
                addRedisCandidates(output, redisCandidates, 1);
                if (output.isEmpty()) { addMysqlCandidatesSafely(output, 1); }
            } else {
                addMysqlCandidatesSafely(output, 1);
                if (output.isEmpty()) { addRedisCandidates(output, redisCandidates, 1); }
            }
            return;
        }
        addMysqlCandidatesSafely(output, 1);
        addRedisCandidates(output, redisCandidates, scheduledBatchSize - output.size());
        if (output.size() < scheduledBatchSize) {
            addMysqlCandidatesSafely(output, scheduledBatchSize);
        }
    }

    private void addMysqlCandidatesSafely(Set<CounterEntityIdentity> output, int desiredCount) {
        try {
            int available = scheduledBatchSize - output.size();
            int desired = Math.min(desiredCount, available);
            if (desired <= 0) { return; }
            int discoveryWindow = Math.min(
                    1_000,
                    Math.max(MYSQL_DISCOVERY_WINDOW_MIN, scheduledBatchSize));
            List<CounterEntityIdentity> identities =
                    persistenceMapper.listOldestReactionSnapshotIdentities(discoveryWindow);
            if (identities == null || identities.isEmpty()) { return; }
            int start = (int) Math.floorMod(mysqlRotation.getAndIncrement(), identities.size());
            int added = 0;
            for (int offset = 0; offset < identities.size() && added < desired; offset++) {
                CounterEntityIdentity identity = identities.get((start + offset) % identities.size());
                if (identity != null && output.add(identity)) { added++; }
            }
        } catch (RuntimeException exception) {
            log.warn("Counter calibration snapshot discovery failed: {}", exception.getMessage());
        }
    }

    private void addRedisCandidates(
            Set<CounterEntityIdentity> output,
            List<CounterEntityIdentity> redisCandidates,
            int limit) {
        if (limit <= 0) { return; }
        int targetSize = Math.min(scheduledBatchSize, output.size() + limit);
        for (CounterEntityIdentity identity : redisCandidates) {
            if (output.size() >= targetSize) { return; }
            output.add(identity);
        }
    }

    private long countBitmapShards(String metric, String entityType, String entityId) {
        Set<String> keys = bitmapIndex.requireShardKeys(metric, entityType, entityId);
        if (keys.isEmpty()) { return 0L; }
        List<Object> values = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : keys) {
                connection.stringCommands().bitCount(key.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        long total = 0L;
        for (Object value : values) {
            if (!(value instanceof Number number) || number.longValue() < 0L) {
                throw new IllegalStateException("Bitmap BITCOUNT returned an invalid value");
            }
            total = Math.addExact(total, number.longValue());
            if (total > UINT32_MAX) {
                throw new IllegalStateException("Reaction count exceeds unsigned Int32");
            }
        }
        return total;
    }

    private static ReconciliationResult mapResult(List<?> raw) {
        if (raw == null || raw.size() != 3
                || !(raw.get(0) instanceof Number like)
                || !(raw.get(1) instanceof Number fav)
                || !(raw.get(2) instanceof Number epoch)
                || like.longValue() < 0L || fav.longValue() < 0L || epoch.longValue() < 0L) {
            throw new IllegalStateException("Counter reconciliation Lua returned an invalid result");
        }
        return new ReconciliationResult(like.longValue(), fav.longValue(), epoch.longValue());
    }

    private static void requireIdentity(String entityType, String entityId) {
        CounterSchema.requirePersistableIdentity(entityType, entityId);
    }

    public record ReconciliationResult(long likeCount, long favCount, long factEpoch) {}

}
