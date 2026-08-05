package com.chtholly.post.service.impl;

import com.chtholly.post.event.PostPublishedEvent;
import com.chtholly.post.model.Post;
import com.chtholly.post.outbox.PostOutboxProjectionProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Starts the durable post projection fast path only after its Outbox row commits. */
@Component
public class PostCommittedSideEffectCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PostCommittedSideEffectCoordinator.class);

    private final PostOutboxProjectionProcessor projectionProcessor;
    private final ApplicationEventPublisher eventPublisher;

    public PostCommittedSideEffectCoordinator(
            PostOutboxProjectionProcessor projectionProcessor,
            ApplicationEventPublisher eventPublisher) {
        this.projectionProcessor = projectionProcessor;
        this.eventPublisher = eventPublisher;
    }

    void afterContentConfirmed(long eventId, long postId) {
        afterProjection(eventId, "PostContentConfirmed", postId);
    }

    void afterMetadataUpdated(long eventId, long postId) {
        afterProjection(eventId, "PostMetadataUpdated", postId);
    }

    void afterTopChanged(long eventId, long postId) {
        afterProjection(eventId, "PostTopChanged", postId);
    }

    void afterVisibilityChanged(long eventId, long postId) {
        afterProjection(eventId, "PostVisibilityChanged", postId);
    }

    void afterPublished(long eventId, long postId, long creatorId, Post post) {
        PostPublishedEvent event = toPublishedEvent(postId, creatorId, post);
        afterCommit(() -> {
            projectSafely(eventId, "PostPublished", postId);
            if (event != null) {
                runSafely("publish PostPublishedEvent", postId, () -> eventPublisher.publishEvent(event));
            }
        });
    }

    void afterDeleted(long eventId, long postId) {
        afterProjection(eventId, "PostDeleted", postId);
    }

    private void afterProjection(long eventId, String eventType, long postId) {
        afterCommit(() -> projectSafely(eventId, eventType, postId));
    }

    private void projectSafely(long eventId, String eventType, long postId) {
        runSafely("project committed Outbox event " + eventId, postId,
                () -> projectionProcessor.process(eventId, eventType, postId));
    }

    private PostPublishedEvent toPublishedEvent(long postId, long creatorId, Post post) {
        if (post == null || post.getPublishTime() == null) {
            return null;
        }
        return new PostPublishedEvent(postId, creatorId, post.getPublishTime(), post.getVisible());
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

    private void runSafely(String operation, long postId, Runnable action) {
        try {
            action.run();
        } catch (Exception failure) {
            log.warn("Post commit effect failed, operation={}, postId={}: {}",
                    operation, postId, failure.getMessage(), failure);
        }
    }
}
