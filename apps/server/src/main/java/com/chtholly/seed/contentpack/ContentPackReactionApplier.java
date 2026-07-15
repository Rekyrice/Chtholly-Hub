package com.chtholly.seed.contentpack;

import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.event.CounterEventPublisher;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.service.CounterService;
import com.chtholly.seed.contentpack.ContentPackDatabaseWriter.ResolvedIdentities;
import com.chtholly.seed.contentpack.model.SeedReactionDefinition;
import com.chtholly.seed.contentpack.model.SeedViewDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.TimeUnit;

/**
 * Reconciles Seed-owned runtime reactions through the public counter boundary after MySQL commit.
 *
 * <p>Only resolved Seed accounts are inspected for removal. Real-user facts and monotonically
 * increasing visitor views are never decremented.
 */
@Component
public final class ContentPackReactionApplier {

    private static final String ENTITY_TYPE = "post";
    private static final String VIEW = "view";

    private final CounterService counterService;
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
            CounterEventPublisher eventPublisher,
            RedissonClient redisson) {
        this(counterService, eventPublisher, redisson, 25, Duration.ofMillis(50));
    }

    /** Creates an applier with explicit poll settings for deterministic package tests. */
    ContentPackReactionApplier(
            CounterService counterService,
            CounterEventPublisher eventPublisher,
            RedissonClient redisson,
            int maxPollAttempts,
            Duration pollInterval) {
        this.counterService = Objects.requireNonNull(counterService, "counterService");
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
     * Applies declared likes/favorites, removes obsolete Seed facts and raises view minima.
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

        Set<ReactionFact> declaredLikes = new HashSet<>();
        Set<ReactionFact> declaredFavs = new HashSet<>();
        for (SeedReactionDefinition reaction : reactions) {
            long postId = identities.postIds().get(reaction.postSeedKey());
            long accountId = identities.accountIds().get(reaction.accountSeedKey());
            ReactionFact fact = new ReactionFact(accountId, postId);
            switch (reaction.type()) {
                case "like" -> {
                    counterService.like(ENTITY_TYPE, String.valueOf(postId), accountId);
                    declaredLikes.add(fact);
                }
                case "fav" -> {
                    counterService.fav(ENTITY_TYPE, String.valueOf(postId), accountId);
                    declaredFavs.add(fact);
                }
                default -> throw new IllegalStateException("validated reaction type changed");
            }
        }

        reconcileObsolete(identities, declaredLikes, declaredFavs);
        List<Long> pendingViews = applyViews(views, identities);
        return new ReactionApplyResult(!pendingViews.isEmpty(), pendingViews);
    }

    private void validate(
            List<SeedReactionDefinition> reactions,
            List<SeedViewDefinition> views,
            ResolvedIdentities identities) {
        for (SeedReactionDefinition reaction : reactions) {
            if (!"like".equals(reaction.type()) && !"fav".equals(reaction.type())) {
                throw new IllegalArgumentException("Unsupported reaction: " + reaction.type());
            }
            requireId(identities.postIds(), reaction.postSeedKey(), "reaction post");
            requireId(identities.accountIds(), reaction.accountSeedKey(), "reaction account");
        }
        for (SeedViewDefinition view : views) {
            requireId(identities.postIds(), view.postSeedKey(), "view post");
            if (view.minimumCount() < 0 || view.minimumCount() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("view baseline outside Int32 range: " + view.seedKey());
            }
        }
    }

    private void reconcileObsolete(
            ResolvedIdentities identities,
            Set<ReactionFact> declaredLikes,
            Set<ReactionFact> declaredFavs) {
        for (long accountId : identities.accountIds().values()) {
            for (long postId : identities.postIds().values()) {
                String entityId = String.valueOf(postId);
                ReactionFact fact = new ReactionFact(accountId, postId);
                if (!declaredLikes.contains(fact)
                        && counterService.isLiked(ENTITY_TYPE, entityId, accountId)) {
                    counterService.unlike(ENTITY_TYPE, entityId, accountId);
                }
                if (!declaredFavs.contains(fact)
                        && counterService.isFaved(ENTITY_TYPE, entityId, accountId)) {
                    counterService.unfav(ENTITY_TYPE, entityId, accountId);
                }
            }
        }
    }

    private List<Long> applyViews(List<SeedViewDefinition> views, ResolvedIdentities identities) {
        List<ViewTarget> awaitingVisibility = new ArrayList<>();
        for (SeedViewDefinition view : views) {
            long postId = identities.postIds().get(view.postSeedKey());
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

    private record ReactionFact(long accountId, long postId) {
    }

    private record ViewTarget(String entityId, long postId, long minimum) {
    }
}
