package com.chtholly.post.service.impl;

import com.chtholly.post.service.PostFeedService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Preserves post mutation cache invalidation order across transaction commit boundaries. */
@Component
public class PostMutationCacheCoordinator {

    private final PostCacheInvalidator cacheInvalidator;
    private final PostFeedService postFeedService;

    /**
     * Creates the post mutation cache coordinator.
     *
     * @param cacheInvalidator detail and public-feed invalidator
     * @param postFeedService personal-feed cache boundary
     */
    public PostMutationCacheCoordinator(
            PostCacheInvalidator cacheInvalidator,
            PostFeedService postFeedService) {
        this.cacheInvalidator = cacheInvalidator;
        this.postFeedService = postFeedService;
    }

    void invalidateBeforeWrite(long postId) {
        cacheInvalidator.invalidate(postId);
    }

    void invalidatePostAfterCommit(long postId) {
        afterCommit(() -> cacheInvalidator.invalidate(postId));
    }

    void invalidateAuthorFeedsAfterCommit(long creatorId) {
        afterCommit(() -> invalidateAuthorFeeds(creatorId));
    }

    void invalidatePublicStructureAfterCommit(long creatorId) {
        afterCommit(() -> {
            cacheInvalidator.invalidateAllPublicFeedPages();
            invalidateAuthorFeeds(creatorId);
        });
    }

    void invalidatePublicationAfterCommit(long postId, long creatorId) {
        afterCommit(() -> {
            cacheInvalidator.invalidate(postId);
            cacheInvalidator.invalidateAllPublicFeedPages();
            invalidateAuthorFeeds(creatorId);
        });
    }

    void invalidateVisibilityAfterCommit(long creatorId) {
        invalidatePublicStructureAfterCommit(creatorId);
    }

    private void invalidateAuthorFeeds(long creatorId) {
        postFeedService.invalidateMyPublishedCache(creatorId);
        postFeedService.invalidateFollowingAuthorCache(creatorId);
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }
}
