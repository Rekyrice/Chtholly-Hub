package com.chtholly.post.service.impl;

import com.chtholly.post.event.PostPublishedEvent;
import com.chtholly.post.model.Post;
import com.chtholly.post.outbox.PostOutboxProjectionProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PostCommittedSideEffectCoordinatorTest {

    @Mock private PostOutboxProjectionProcessor projectionProcessor;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PostCommittedSideEffectCoordinator coordinator;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        coordinator = new PostCommittedSideEffectCoordinator(projectionProcessor, eventPublisher);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void publicationProjectionRunsThroughTheSharedProcessorOnlyAfterCommit() {
        Instant publishTime = Instant.parse("2026-07-01T00:00:00Z");
        Post post = Post.builder()
                .id(42L)
                .creatorId(7L)
                .publishTime(publishTime)
                .visible("public")
                .build();

        coordinator.afterPublished(101L, 42L, 7L, post);

        verify(projectionProcessor, never()).process(101L, "PostPublished", 42L);
        verify(eventPublisher, never()).publishEvent(any(Object.class));

        runAfterCommit();

        verify(projectionProcessor).process(101L, "PostPublished", 42L);
        ArgumentCaptor<PostPublishedEvent> event = ArgumentCaptor.forClass(PostPublishedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new PostPublishedEvent(42L, 7L, publishTime, "public"));
    }

    @Test
    void rolledBackPublicationRunsNoProjectionOrLegacyEvent() {
        Post post = Post.builder()
                .id(42L)
                .creatorId(7L)
                .publishTime(Instant.parse("2026-07-01T00:00:00Z"))
                .visible("public")
                .build();

        coordinator.afterPublished(101L, 42L, 7L, post);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(projectionProcessor, never()).process(101L, "PostPublished", 42L);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void everyPostOutboxTypeUsesTheSameProcessorAfterCommit() {
        coordinator.afterContentConfirmed(101L, 11L);
        coordinator.afterMetadataUpdated(102L, 12L);
        coordinator.afterTopChanged(103L, 13L);
        coordinator.afterVisibilityChanged(104L, 14L);
        coordinator.afterDeleted(105L, 15L);

        runAfterCommit();

        verify(projectionProcessor).process(101L, "PostContentConfirmed", 11L);
        verify(projectionProcessor).process(102L, "PostMetadataUpdated", 12L);
        verify(projectionProcessor).process(103L, "PostTopChanged", 13L);
        verify(projectionProcessor).process(104L, "PostVisibilityChanged", 14L);
        verify(projectionProcessor).process(105L, "PostDeleted", 15L);
    }

    @Test
    void immediateProjectionFailureDoesNotHideTheCommittedCommandAndRemainsRecoverable() {
        doThrow(new IllegalStateException("RAG unavailable"))
                .when(projectionProcessor).process(101L, "PostContentConfirmed", 42L);
        coordinator.afterContentConfirmed(101L, 42L);

        assertThatCode(this::runAfterCommit).doesNotThrowAnyException();

        verify(projectionProcessor).process(101L, "PostContentConfirmed", 42L);
    }

    private void runAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }
}
