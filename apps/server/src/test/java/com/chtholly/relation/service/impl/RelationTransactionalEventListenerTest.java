package com.chtholly.relation.service.impl;

import com.chtholly.notification.event.FollowCreatedEvent;
import com.chtholly.notification.listener.NotificationEventListener;
import com.chtholly.notification.model.NotificationType;
import com.chtholly.notification.service.NotificationService;
import com.chtholly.post.feed.FeedTimelineService;
import com.chtholly.post.listener.FeedTimelineListener;
import com.chtholly.relation.event.FollowCanceledEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Verifies relation side effects are dispatched only for committed facts. */
class RelationTransactionalEventListenerTest {

    private AnnotationConfigApplicationContext context;
    private NotificationService notificationService;
    private FeedTimelineService feedTimelineService;
    private ApplicationEventPublisher eventPublisher;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        feedTimelineService = mock(FeedTimelineService.class);

        context = new AnnotationConfigApplicationContext();
        context.register(TransactionEventConfiguration.class);
        context.registerBean(NotificationService.class, () -> notificationService);
        context.registerBean(FeedTimelineService.class, () -> feedTimelineService);
        context.registerBean(NotificationEventListener.class);
        context.registerBean(FeedTimelineListener.class);
        context.refresh();

        eventPublisher = context;
        transactions = new TransactionTemplate(
                context.getBean(PlatformTransactionManager.class));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void relationSideEffectsWaitUntilThePublishingTransactionCommits() {
        transactions.executeWithoutResult(status -> {
            publishRelationEvents();

            verifyNoInteractions(notificationService, feedTimelineService);
        });

        verifyCommittedSideEffects();
    }

    @Test
    void rolledBackRelationEventsDoNotProduceSideEffects() {
        transactions.executeWithoutResult(status -> {
            publishRelationEvents();
            status.setRollbackOnly();
        });

        verifyNoInteractions(notificationService, feedTimelineService);
    }

    @Test
    void notificationFailurePreventsTheRelationshipTransactionFromCommittingSilently() {
        doThrow(new IllegalStateException("notification unavailable"))
                .when(notificationService)
                .create(eq(22L), eq(NotificationType.FOLLOW), any());

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                        eventPublisher.publishEvent(new FollowCreatedEvent(
                                11L, "Alice", "avatar.png", 22L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("notification unavailable");
    }

    @Test
    void nonTransactionalPublicationDoesNotBypassTheCommitBoundary() {
        publishRelationEvents();

        verifyNoInteractions(notificationService, feedTimelineService);
    }

    private void publishRelationEvents() {
        eventPublisher.publishEvent(new FollowCreatedEvent(
                11L, "Alice", "avatar.png", 22L));
        eventPublisher.publishEvent(new FollowCanceledEvent(11L, 22L));
    }

    private void verifyCommittedSideEffects() {
        verify(notificationService).create(
                eq(22L), eq(NotificationType.FOLLOW), any());
        verify(feedTimelineService).removeAuthorFromTimeline(11L, 22L);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    @EnableTransactionManagement
    static class TransactionEventConfiguration {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new TestTransactionManager();
        }

        @Bean(name = "notificationExecutor")
        Executor notificationExecutor() {
            return new SyncTaskExecutor();
        }
    }

    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(
                Object transaction,
                TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
