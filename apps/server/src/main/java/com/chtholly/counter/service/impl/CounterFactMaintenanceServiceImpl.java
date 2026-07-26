package com.chtholly.counter.service.impl;

import com.chtholly.counter.mapper.CounterPersistenceMapper;
import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.mapper.CounterReactionMapper;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.service.CounterFactMaintenanceService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/** Reconciles managed historical reactions in MySQL before rebuilding Redis projections. */
@Service
public class CounterFactMaintenanceServiceImpl implements CounterFactMaintenanceService {

    private static final int MYSQL_BATCH_SIZE = 500;
    private static final String ENTITY_TYPE_POST = "post";
    private static final String METRIC_LIKE = "like";
    private static final String METRIC_FAV = "fav";

    private final CounterReactionMapper reactionMapper;
    private final CounterPersistenceMapper persistenceMapper;
    private final CounterReactionProjectionRebuilder projectionRebuilder;
    private final CounterCalibrationService calibrationService;
    private final PlatformTransactionManager transactionManager;
    private final RedissonClient redisson;

    public CounterFactMaintenanceServiceImpl(
            CounterReactionMapper reactionMapper,
            CounterPersistenceMapper persistenceMapper,
            CounterReactionProjectionRebuilder projectionRebuilder,
            CounterCalibrationService calibrationService,
            PlatformTransactionManager transactionManager,
            RedissonClient redisson) {
        this.reactionMapper = Objects.requireNonNull(reactionMapper, "reactionMapper");
        this.persistenceMapper = Objects.requireNonNull(persistenceMapper, "persistenceMapper");
        this.projectionRebuilder = Objects.requireNonNull(
                projectionRebuilder, "projectionRebuilder");
        this.calibrationService = Objects.requireNonNull(calibrationService, "calibrationService");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.redisson = Objects.requireNonNull(redisson, "redisson");
    }

    /** {@inheritDoc} */
    @Override
    public ReactionReconciliationResult reconcileManagedPostReactions(
            Set<Long> managedUserIds,
            Set<Long> authoritativePostIds,
            Map<Long, ManagedPostReactionState> desiredByPost) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Counter fact maintenance cannot join an active transaction");
        }
        ValidatedRequest request = validateAndCopyInput(
                managedUserIds, authoritativePostIds, desiredByPost);
        Map<Long, PostReactionReconciliationResult> results = new LinkedHashMap<>();
        for (Long postId : request.authoritativePostIds()) {
            ManagedPostReactionState desired = request.desiredByPost().getOrDefault(
                    postId, EmptyDesiredState.VALUE);
            results.put(postId, reconcilePost(postId, request.managedUserIds(), desired));
        }
        return new ReactionReconciliationResult(results);
    }

    private PostReactionReconciliationResult reconcilePost(
            long postId,
            Set<Long> managedUserIds,
            ManagedPostReactionState desired) {
        String entityId = Long.toString(postId);
        RLock lock = redisson.getLock(
                CounterKeys.factMaintenanceLockKey(ENTITY_TYPE_POST, entityId));
        boolean locked = false;
        RuntimeException primaryFailure = null;
        try {
            try {
                locked = lock.tryLock(0L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while acquiring counter fact maintenance lock",
                        exception);
            }
            if (!locked) {
                throw new IllegalStateException(
                        "Counter fact maintenance lock is busy");
            }
            projectionRebuilder.invalidateComplete(ENTITY_TYPE_POST, entityId);

            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            ManagedMutation mutation = transaction.execute(status ->
                    reconcileManagedFacts(postId, managedUserIds, desired));
            if (mutation == null) {
                throw new IllegalStateException(
                        "Counter managed reaction transaction returned no result");
            }

            CounterCalibrationService.ReconciliationResult rebuilt =
                    calibrationService.reconcileEntity(ENTITY_TYPE_POST, entityId);
            return new PostReactionReconciliationResult(
                    postId,
                    mutation.inserted(),
                    mutation.deleted(),
                    rebuilt.likeCount(),
                    rebuilt.favCount());
        } catch (RuntimeException exception) {
            primaryFailure = exception;
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

    private ManagedMutation reconcileManagedFacts(
            long postId,
            Set<Long> managedUserIds,
            ManagedPostReactionState desired) {
        String entityId = Long.toString(postId);
        persistenceMapper.ensureReactionSnapshots(ENTITY_TYPE_POST, entityId);
        requireSingleEpoch(persistenceMapper.lockReactionEpochs(ENTITY_TYPE_POST, entityId));

        List<CounterReactionKey> managedKeys = new ArrayList<>(managedUserIds.size() * 2);
        for (Long userId : managedUserIds) {
            managedKeys.add(new CounterReactionKey(
                    ENTITY_TYPE_POST, entityId, METRIC_LIKE, userId));
        }
        for (Long userId : managedUserIds) {
            managedKeys.add(new CounterReactionKey(
                    ENTITY_TYPE_POST, entityId, METRIC_FAV, userId));
        }
        Set<CounterReactionKey> existing = findExistingManagedKeys(managedKeys);
        Set<CounterReactionKey> desiredKeys = desiredKeys(entityId, desired);

        List<CounterReactionKey> inserts = managedKeys.stream()
                .filter(desiredKeys::contains)
                .filter(key -> !existing.contains(key))
                .toList();
        List<CounterReactionKey> deletes = managedKeys.stream()
                .filter(existing::contains)
                .filter(key -> !desiredKeys.contains(key))
                .toList();
        long inserted = insertBatches(inserts);
        long deleted = deleteBatches(deletes);
        return new ManagedMutation(inserted, deleted);
    }

    private Set<CounterReactionKey> findExistingManagedKeys(
            List<CounterReactionKey> managedKeys) {
        Set<CounterReactionKey> requested = Set.copyOf(managedKeys);
        Set<CounterReactionKey> existing = new LinkedHashSet<>();
        for (int from = 0; from < managedKeys.size(); from += MYSQL_BATCH_SIZE) {
            List<CounterReactionKey> batch = managedKeys.subList(
                    from, Math.min(from + MYSQL_BATCH_SIZE, managedKeys.size()));
            List<CounterReactionKey> rows = reactionMapper.findExisting(batch);
            if (rows == null) {
                throw new IllegalStateException(
                        "Counter managed reaction MySQL query returned no result");
            }
            for (CounterReactionKey row : rows) {
                if (row == null || !requested.contains(row) || !existing.add(row)) {
                    throw new IllegalStateException(
                            "Counter managed reaction MySQL query returned an invalid row");
                }
            }
        }
        return Set.copyOf(existing);
    }

    private long insertBatches(List<CounterReactionKey> inserts) {
        long total = 0L;
        for (int from = 0; from < inserts.size(); from += MYSQL_BATCH_SIZE) {
            List<CounterReactionKey> batch = inserts.subList(
                    from, Math.min(from + MYSQL_BATCH_SIZE, inserts.size()));
            int affected = reactionMapper.insertAllIgnore(batch);
            if (affected != batch.size()) {
                throw new IllegalStateException(
                        "Counter managed reaction insert returned an invalid row count");
            }
            total = Math.addExact(total, affected);
        }
        return total;
    }

    private long deleteBatches(List<CounterReactionKey> deletes) {
        long total = 0L;
        for (int from = 0; from < deletes.size(); from += MYSQL_BATCH_SIZE) {
            List<CounterReactionKey> batch = deletes.subList(
                    from, Math.min(from + MYSQL_BATCH_SIZE, deletes.size()));
            int affected = reactionMapper.deleteAll(batch);
            if (affected != batch.size()) {
                throw new IllegalStateException(
                        "Counter managed reaction delete returned an invalid row count");
            }
            total = Math.addExact(total, affected);
        }
        return total;
    }

    private static Set<CounterReactionKey> desiredKeys(
            String entityId,
            ManagedPostReactionState desired) {
        Set<CounterReactionKey> keys = new HashSet<>();
        for (Long userId : desired.likedUserIds()) {
            keys.add(new CounterReactionKey(
                    ENTITY_TYPE_POST, entityId, METRIC_LIKE, userId));
        }
        for (Long userId : desired.favedUserIds()) {
            keys.add(new CounterReactionKey(
                    ENTITY_TYPE_POST, entityId, METRIC_FAV, userId));
        }
        return Set.copyOf(keys);
    }

    private static ValidatedRequest validateAndCopyInput(
            Set<Long> managedUserIds,
            Set<Long> authoritativePostIds,
            Map<Long, ManagedPostReactionState> desiredByPost) {
        Objects.requireNonNull(managedUserIds, "managedUserIds");
        Objects.requireNonNull(authoritativePostIds, "authoritativePostIds");
        Objects.requireNonNull(desiredByPost, "desiredByPost");
        if (managedUserIds.isEmpty()) {
            throw new IllegalArgumentException("managedUserIds must not be empty");
        }
        if (authoritativePostIds.isEmpty()) {
            throw new IllegalArgumentException("authoritativePostIds must not be empty");
        }

        TreeSet<Long> users = positiveIds(managedUserIds, "managed user");
        TreeSet<Long> posts = positiveIds(authoritativePostIds, "authoritative post");
        Map<Long, ManagedPostReactionState> desired = new LinkedHashMap<>();
        for (Map.Entry<Long, ManagedPostReactionState> entry : desiredByPost.entrySet()) {
            Long postId = entry.getKey();
            if (postId == null || !posts.contains(postId)) {
                throw new IllegalArgumentException(
                        "desired reaction post must be authoritative");
            }
            ManagedPostReactionState state =
                    Objects.requireNonNull(entry.getValue(), "desired reaction state");
            if (!users.containsAll(state.likedUserIds())
                    || !users.containsAll(state.favedUserIds())) {
                throw new IllegalArgumentException(
                        "desired reactions must belong to managed users");
            }
            positiveIds(state.likedUserIds(), "desired like user");
            positiveIds(state.favedUserIds(), "desired favorite user");
            desired.put(
                    postId,
                    new ManagedPostReactionState(
                            new TreeSet<>(state.likedUserIds()),
                            new TreeSet<>(state.favedUserIds())));
        }
        return new ValidatedRequest(
                Collections.unmodifiableSet(new LinkedHashSet<>(users)),
                List.copyOf(posts),
                Map.copyOf(desired));
    }

    private static TreeSet<Long> positiveIds(Set<Long> values, String label) {
        TreeSet<Long> copy = new TreeSet<>();
        for (Long value : values) {
            if (value == null || value <= 0L) {
                throw new IllegalArgumentException(label + " ID must be positive");
            }
            copy.add(value);
        }
        return copy;
    }

    private static long requireSingleEpoch(List<Long> epochs) {
        if (epochs == null
                || epochs.size() != 2
                || epochs.get(0) == null
                || epochs.get(1) == null
                || epochs.get(0) < 0L
                || !epochs.get(0).equals(epochs.get(1))) {
            throw new IllegalStateException(
                    "Counter managed reaction snapshot epoch is inconsistent");
        }
        return epochs.get(0);
    }

    private record ValidatedRequest(
            Set<Long> managedUserIds,
            List<Long> authoritativePostIds,
            Map<Long, ManagedPostReactionState> desiredByPost) {}

    private record ManagedMutation(long inserted, long deleted) {}

    private static final class EmptyDesiredState {
        private static final ManagedPostReactionState VALUE =
                new ManagedPostReactionState(Set.of(), Set.of());

        private EmptyDesiredState() {}
    }
}
