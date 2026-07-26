package com.chtholly.counter.service.impl;

import com.chtholly.counter.mapper.CounterEntityIdentity;
import com.chtholly.counter.mapper.CounterPersistenceMapper;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Rebuilds Redis reaction projections and durable counts from authoritative MySQL facts. */
@Service
public class CounterCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(CounterCalibrationService.class);
    private static final int MYSQL_DISCOVERY_WINDOW_MIN = 50;

    private final RedissonClient redisson;
    private final CounterPersistenceMapper persistenceMapper;
    private final CounterReactionProjectionRebuilder projectionRebuilder;
    private final PlatformTransactionManager transactionManager;
    private final boolean scheduledEnabled;
    private final int scheduledBatchSize;
    private final AtomicLong mysqlRotation = new AtomicLong();

    public CounterCalibrationService(
            RedissonClient redisson,
            CounterPersistenceMapper persistenceMapper,
            CounterReactionProjectionRebuilder projectionRebuilder,
            PlatformTransactionManager transactionManager,
            @Value("${counter.calibration.enabled:true}") boolean scheduledEnabled,
            @Value("${counter.calibration.batch-size:50}") int scheduledBatchSize) {
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        this.persistenceMapper = Objects.requireNonNull(persistenceMapper, "persistenceMapper");
        this.projectionRebuilder = Objects.requireNonNull(projectionRebuilder, "projectionRebuilder");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        if (scheduledBatchSize < 1 || scheduledBatchSize > 1_000) {
            throw new IllegalArgumentException("scheduledBatchSize must be between 1 and 1000");
        }
        this.scheduledEnabled = scheduledEnabled;
        this.scheduledBatchSize = scheduledBatchSize;
    }

    /**
     * Rebuilds one entity under a Redis maintenance fence and locked MySQL snapshot rows.
     *
     * @param entityType entity type
     * @param entityId entity ID
     * @return rebuilt absolute counts and their new fact epoch
     */
    public ReconciliationResult reconcileEntity(String entityType, String entityId) {
        CounterSchema.requirePersistableIdentity(entityType, entityId);
        RLock lock = redisson.getLock(CounterKeys.factMaintenanceLockKey(entityType, entityId));
        boolean locked = false;
        boolean begun = false;
        String token = UUID.randomUUID().toString();
        RuntimeException primaryFailure = null;
        try {
            locked = lock.tryLock(0L, TimeUnit.MILLISECONDS);
            if (!locked) {
                throw new IllegalStateException("Counter reconciliation lock is busy");
            }
            projectionRebuilder.begin(entityType, entityId, token);
            begun = true;

            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            ReconciliationResult result = transaction.execute(status ->
                    reconcileInTransaction(entityType, entityId, token));
            if (result == null) {
                throw new IllegalStateException("Counter reconciliation transaction returned no result");
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            primaryFailure = new IllegalStateException("Interrupted while reconciling counters", exception);
            if (begun) {
                abortWithSuppressed(entityType, entityId, token, primaryFailure);
            }
            throw primaryFailure;
        } catch (RuntimeException exception) {
            primaryFailure = exception;
            if (begun) {
                abortWithSuppressed(entityType, entityId, token, exception);
            }
            throw exception;
        } finally {
            if (locked) {
                try {
                    lock.unlock();
                } catch (RuntimeException unlockFailure) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(unlockFailure);
                    } else {
                        throw unlockFailure;
                    }
                }
            }
        }
    }

    /** Periodically repairs a bounded rotation of MySQL-backed reaction entities. */
    @Scheduled(fixedDelayString = "${counter.calibration.fixed-delay:PT5M}")
    public void reconcileScheduled() {
        if (!scheduledEnabled) {
            return;
        }
        int discoveryWindow = Math.min(
                1_000,
                Math.max(MYSQL_DISCOVERY_WINDOW_MIN, scheduledBatchSize));
        List<CounterEntityIdentity> candidates;
        try {
            candidates = persistenceMapper.listOldestReactionSnapshotIdentities(discoveryWindow);
            if (candidates == null) {
                throw new IllegalStateException("Counter reconciliation candidate query returned no result");
            }
        } catch (RuntimeException exception) {
            log.warn("Counter reconciliation candidate discovery failed: {}", exception.getMessage());
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }

        int start = (int) Math.floorMod(mysqlRotation.getAndIncrement(), candidates.size());
        int processed = 0;
        for (int offset = 0; offset < candidates.size() && processed < scheduledBatchSize; offset++) {
            CounterEntityIdentity candidate = candidates.get((start + offset) % candidates.size());
            if (candidate == null) {
                continue;
            }
            processed++;
            try {
                CounterSchema.requirePersistableIdentity(
                        candidate.entityType(), candidate.entityId());
                reconcileEntity(candidate.entityType(), candidate.entityId());
            } catch (RuntimeException exception) {
                log.warn(
                        "Counter reconciliation failed entityType={} entityId={}: {}",
                        candidate.entityType(),
                        candidate.entityId(),
                        exception.getMessage());
            }
        }
    }

    private ReconciliationResult reconcileInTransaction(
            String entityType,
            String entityId,
            String token) {
        persistenceMapper.ensureReactionSnapshots(entityType, entityId);
        long currentEpoch = requireSingleEpoch(
                persistenceMapper.lockReactionEpochs(entityType, entityId));
        long nextEpoch = Math.addExact(currentEpoch, 1L);
        CounterReactionProjectionRebuilder.RebuildResult rebuilt =
                projectionRebuilder.rebuild(entityType, entityId, token, nextEpoch);
        if (rebuilt == null
                || rebuilt.likeCount() < 0L
                || rebuilt.favCount() < 0L
                || rebuilt.factEpoch() != nextEpoch) {
            throw new IllegalStateException("Counter reaction rebuild returned an invalid result");
        }
        persistenceMapper.replaceReactionSnapshots(
                entityType,
                entityId,
                rebuilt.likeCount(),
                rebuilt.favCount(),
                nextEpoch);
        return new ReconciliationResult(rebuilt.likeCount(), rebuilt.favCount(), nextEpoch);
    }

    private void abortWithSuppressed(
            String entityType,
            String entityId,
            String token,
            RuntimeException primaryFailure) {
        try {
            projectionRebuilder.abort(entityType, entityId, token);
        } catch (RuntimeException abortFailure) {
            primaryFailure.addSuppressed(abortFailure);
        }
    }

    private static long requireSingleEpoch(List<Long> epochs) {
        if (epochs == null
                || epochs.size() != 2
                || epochs.get(0) == null
                || epochs.get(1) == null
                || epochs.get(0) < 0L
                || !epochs.get(0).equals(epochs.get(1))) {
            throw new IllegalStateException("Counter reaction snapshot epoch is inconsistent");
        }
        return epochs.get(0);
    }

    /** Absolute result of one MySQL-driven reaction reconciliation. */
    public record ReconciliationResult(long likeCount, long favCount, long factEpoch) {}
}
