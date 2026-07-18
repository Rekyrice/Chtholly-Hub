package com.chtholly.counter.service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reconciles authoritative historical post reaction facts without publishing user-facing events.
 *
 * <p>This maintenance-only contract is intentionally separate from {@link CounterService}; it
 * accepts complete desired facts for managed users and never models an interactive toggle.
 */
public interface CounterFactMaintenanceService {

    /**
     * Reconciles like and favorite facts for managed users on authoritative posts.
     *
     * @param managedUserIds users whose reaction facts are fully controlled by the caller
     * @param authoritativePostIds posts whose managed reaction facts are authoritative
     * @param desiredByPost desired managed reactions; an absent post means no managed reactions
     * @return per-post reconciliation statistics
     */
    ReactionReconciliationResult reconcileManagedPostReactions(
            Set<Long> managedUserIds,
            Set<Long> authoritativePostIds,
            Map<Long, ManagedPostReactionState> desiredByPost);

    /** Complete desired reaction membership for managed users on one post. */
    record ManagedPostReactionState(Set<Long> likedUserIds, Set<Long> favedUserIds) {
        /**
         * Creates an immutable desired reaction state.
         *
         * @param likedUserIds managed users that must have a like fact
         * @param favedUserIds managed users that must have a favorite fact
         */
        public ManagedPostReactionState {
            likedUserIds = Set.copyOf(Objects.requireNonNull(likedUserIds, "likedUserIds"));
            favedUserIds = Set.copyOf(Objects.requireNonNull(favedUserIds, "favedUserIds"));
        }
    }

    /** Statistics returned by the atomic reconciliation of one post. */
    record PostReactionReconciliationResult(
            long postId,
            long managedSetCount,
            long managedClearCount,
            long orphanClearCount,
            long likeTotal,
            long favTotal) {}

    /** Aggregate result for a reconciliation batch. */
    record ReactionReconciliationResult(Map<Long, PostReactionReconciliationResult> posts) {
        /**
         * Creates an immutable batch result.
         *
         * @param posts per-post results keyed by authoritative post ID
         */
        public ReactionReconciliationResult {
            posts = Map.copyOf(Objects.requireNonNull(posts, "posts"));
        }
    }
}
