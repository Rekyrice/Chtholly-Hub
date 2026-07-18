package com.chtholly.seed.contentpack;

import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.event.CounterEventPublisher;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.service.CounterService;
import com.chtholly.counter.service.CounterFactMaintenanceService;
import com.chtholly.counter.service.CounterFactMaintenanceService.ManagedPostReactionState;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.ResolvedIdentities;
import com.chtholly.seed.contentpack.model.SeedReactionDefinition;
import com.chtholly.seed.contentpack.model.SeedViewDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.TimeUnit;

/**
 * Reconciles declared historical reactions without emitting user-facing interaction events.
 *
 * <p>Likes and favorites are a complete managed-user fact set on every authoritative target. The
 * maintenance boundary sets and clears only Seed-owned bits, preserves valid natural users, and
 * rebuilds the post counters exactly. Visitor views remain monotonic minimum baselines.
 */
@Component
public final class ContentPackReactionApplier {

    private static final String ENTITY_TYPE = "post";
    private static final String VIEW = "view";

    private final CounterService counterService;
    private final CounterFactMaintenanceService factMaintenanceService;
    private final CounterEventPublisher eventPublisher;
    private final RedissonClient redisson;
    private final int maxPollAttempts;
    private final Duration pollInterval;

    /**
     * Creates an applier with a short bounded aggregation visibility poll.
     *
     * @param counterService public idempotent reaction and count service
     * @param eventPublisher asynchronous counter aggregation publisher
     * @param redisson distributed lock client shared by importer processes
     */
    @Autowired
    public ContentPackReactionApplier(
            CounterService counterService,
            CounterFactMaintenanceService factMaintenanceService,
            CounterEventPublisher eventPublisher,
            RedissonClient redisson) {
        this(counterService, factMaintenanceService, eventPublisher, redisson, 25, Duration.ofMillis(50));
    }

    /** Creates an applier with explicit poll settings for deterministic package tests. */
    ContentPackReactionApplier(
            CounterService counterService,
            CounterFactMaintenanceService factMaintenanceService,
            CounterEventPublisher eventPublisher,
            RedissonClient redisson,
            int maxPollAttempts,
            Duration pollInterval) {
        this.counterService = Objects.requireNonNull(counterService, "counterService");
        this.factMaintenanceService = Objects.requireNonNull(factMaintenanceService, "factMaintenanceService");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        if (maxPollAttempts < 1) {
            throw new IllegalArgumentException("maxPollAttempts must be positive");
        }
        this.maxPollAttempts = maxPollAttempts;
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must not be negative");
        }
    }

    /**
     * Reconciles declared managed likes/favorites and raises view minima.
     *
     * @param reactions declared Seed likes and favorites
     * @param views minimum non-decreasing view baselines
     * @param identities stable account and post IDs returned by the database writer
     * @return bounded view aggregation visibility result
     */
    public ReactionApplyResult apply(
            List<SeedReactionDefinition> reactions,
            List<SeedViewDefinition> views,
            ResolvedIdentities identities) {
        Objects.requireNonNull(reactions, "reactions");
        Objects.requireNonNull(views, "views");
        Objects.requireNonNull(identities, "identities");
        validate(reactions, views, identities);

        reconcileManagedReactions(reactions, identities);

        List<Long> pendingViews = applyViews(views, identities);
        return new ReactionApplyResult(!pendingViews.isEmpty(), pendingViews);
    }

    private void reconcileManagedReactions(
            List<SeedReactionDefinition> reactions, ResolvedIdentities identities) {
        Set<Long> managedUserIds = new LinkedHashSet<>(identities.accountIds().values());
        Set<Long> authoritativePostIds = new LinkedHashSet<>(identities.postIds().values());
        authoritativePostIds.addAll(identities.externalPostIdsBySlug().values());
        if (managedUserIds.isEmpty() || authoritativePostIds.isEmpty()) {
            return;
        }

        Map<Long, MutableReactionState> mutableByPost = new LinkedHashMap<>();
        for (SeedReactionDefinition reaction : reactions) {
            long postId = resolvePostId(
                    identities, reaction.postSeedKey(), reaction.postSlug(), "reaction post");
            long accountId = requireId(
                    identities.accountIds(), reaction.accountSeedKey(), "reaction account");
            MutableReactionState state = mutableByPost.computeIfAbsent(postId, ignored -> new MutableReactionState());
            switch (reaction.type()) {
                case "like" -> state.likes.add(accountId);
                case "fav" -> state.favorites.add(accountId);
                default -> throw new IllegalStateException("validated reaction type changed");
            }
        }

        Map<Long, ManagedPostReactionState> desiredByPost = new LinkedHashMap<>();
        for (Map.Entry<Long, MutableReactionState> entry : mutableByPost.entrySet()) {
            desiredByPost.put(entry.getKey(), new ManagedPostReactionState(
                    entry.getValue().likes, entry.getValue().favorites));
        }
        factMaintenanceService.reconcileManagedPostReactions(
                Set.copyOf(managedUserIds), Set.copyOf(authoritativePostIds), Map.copyOf(desiredByPost));
    }

    private void validate(
            List<SeedReactionDefinition> reactions,
            List<SeedViewDefinition> views,
            ResolvedIdentities identities) {
        for (SeedReactionDefinition reaction : reactions) {
            if (!"like".equals(reaction.type()) && !"fav".equals(reaction.type())) {
                throw new IllegalArgumentException("Unsupported reaction: " + reaction.type());
            }
            resolvePostId(identities, reaction.postSeedKey(), reaction.postSlug(), "reaction post");
            requireId(identities.accountIds(), reaction.accountSeedKey(), "reaction account");
        }
        for (SeedViewDefinition view : views) {
            resolvePostId(identities, view.postSeedKey(), view.postSlug(), "view post");
            if (view.minimumCount() < 0 || view.minimumCount() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("view baseline outside Int32 range: " + view.seedKey());
            }
        }
    }

    private List<Long> applyViews(List<SeedViewDefinition> views, ResolvedIdentities identities) {
        List<ViewTarget> awaitingVisibility = new ArrayList<>();
        for (SeedViewDefinition view : views) {
            long postId = resolvePostId(identities, view.postSeedKey(), view.postSlug(), "view post");
            String entityId = String.valueOf(postId);
            publishViewDeltaUnderLock(view, entityId, identities.namespace());
            awaitingVisibility.add(new ViewTarget(entityId, postId, view.minimumCount()));
        }
        return awaitViews(awaitingVisibility);
    }

    private List<Long> awaitViews(List<ViewTarget> awaitingVisibility) {
        List<ViewTarget> pending = new ArrayList<>(awaitingVisibility);
        for (int attempt = 0; attempt < maxPollAttempts; attempt++) {
            if (pending.isEmpty()) {
                break;
            }
            if (pollInterval.toNanos() > 0) {
                LockSupport.parkNanos(pollInterval.toNanos());
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
            var iterator = pending.iterator();
            while (iterator.hasNext()) {
                ViewTarget view = iterator.next();
                long current = effectiveViews(view.entityId());
                if (current >= view.minimum()) {
                    iterator.remove();
                }
            }
        }
        return pending.stream().map(ViewTarget::postId).distinct().toList();
    }

    private long effectiveViews(String entityId) {
        return counterService.getEffectiveCount(ENTITY_TYPE, entityId, VIEW);
    }

    private void publishViewDeltaUnderLock(SeedViewDefinition view, String entityId, String namespace) {
        RLock lock = redisson.getLock("lock:seed-content:view-baseline:post:" + entityId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(5L, 5L, TimeUnit.SECONDS);
            if (!acquired) {
                return;
            }
            long effective = counterService.getEffectiveCount(ENTITY_TYPE, entityId, VIEW);
            if (effective >= view.minimumCount()) {
                return;
            }
            int delta = Math.toIntExact(view.minimumCount() - effective);
            eventPublisher.publish(CounterEvent.of(
                    ENTITY_TYPE, entityId, VIEW, CounterSchema.IDX_VIEW, 0L, delta,
                    viewEventId(namespace, entityId, view.minimumCount())));
            awaitEffectiveCount(entityId, view.minimumCount());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String viewEventId(String namespace, String entityId, long minimum) {
        return "seed-view-baseline:" + namespace.length() + ":" + namespace + ":" + entityId + ":" + minimum;
    }

    private void awaitEffectiveCount(String entityId, long minimum) {
        for (int attempt = 0; attempt < maxPollAttempts; attempt++) {
            if (counterService.getEffectiveCount(ENTITY_TYPE, entityId, VIEW) >= minimum) {
                return;
            }
            if (pollInterval.toNanos() > 0) {
                LockSupport.parkNanos(pollInterval.toNanos());
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
            }
        }
    }

    private static long requireId(Map<String, Long> values, String key, String field) {
        Long id = values.get(key);
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("missing resolved " + field + ": " + key);
        }
        return id;
    }

    private static long resolvePostId(
            ResolvedIdentities identities, String postSeedKey, String postSlug, String field) {
        if (postSeedKey != null && !postSeedKey.isBlank()) {
            return requireId(identities.postIds(), postSeedKey, field);
        }
        return requireId(identities.externalPostIdsBySlug(), postSlug, field);
    }

    /**
     * Result of the bounded asynchronous view-count visibility check.
     *
     * @param partial whether at least one view baseline was not visible before the bound
     * @param pendingViewPostIds post IDs whose public count remained below the declared minimum
     */
    public record ReactionApplyResult(boolean partial, List<Long> pendingViewPostIds) {
        public ReactionApplyResult {
            pendingViewPostIds = List.copyOf(pendingViewPostIds);
        }
    }

    private record ViewTarget(String entityId, long postId, long minimum) {
    }

    private static final class MutableReactionState {
        private final Set<Long> likes = new LinkedHashSet<>();
        private final Set<Long> favorites = new LinkedHashSet<>();
    }
}
