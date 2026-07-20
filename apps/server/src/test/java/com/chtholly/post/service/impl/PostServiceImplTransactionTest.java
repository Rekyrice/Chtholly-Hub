package com.chtholly.post.service.impl;

import com.chtholly.counter.service.UserCounterService;
import com.chtholly.llm.rag.PostRagIndexer;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.service.PostFeedService;
import com.chtholly.relation.outbox.OutboxMapper;
import com.chtholly.search.index.SearchIndexService;
import com.chtholly.storage.config.OssProperties;
import com.chtholly.tag.service.TagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostServiceImplTransactionTest {

    @Mock private PostMapper mapper;
    @Mock private UserCounterService userCounterService;
    @Mock private PostCacheInvalidator cacheInvalidator;
    @Mock private PostRagIndexer ragIndexService;
    @Mock private OutboxMapper outboxMapper;
    @Mock private TagService tagService;
    @Mock private SearchIndexService searchIndexService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PostFeedService postFeedService;
    @Mock private PostDetailQueryService detailQueryService;
    @Mock private PostBackgroundQueryService backgroundQueryService;

    private PostServiceImpl service;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        service = new PostServiceImpl(
                mapper, new SnowflakeIdGenerator(), new ObjectMapper(), new OssProperties(), userCounterService,
                cacheInvalidator, ragIndexService, outboxMapper, tagService, searchIndexService, eventPublisher,
                postFeedService, detailQueryService, backgroundQueryService);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void updateTopRunsSecondInvalidationOnlyAfterCommit() {
        when(mapper.updateTop(42L, 7L, true)).thenReturn(1);

        service.updateTop(7L, 42L, true);

        verify(cacheInvalidator).invalidate(42L);
        verify(postFeedService, never()).invalidateMyPublishedCache(7L);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isNotEmpty();

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(cacheInvalidator, times(2)).invalidate(42L);
        verify(postFeedService).invalidateMyPublishedCache(7L);
    }

    @Test
    void rollbackDoesNotRunAfterCommitInvalidation() {
        when(mapper.updateTop(42L, 7L, true)).thenReturn(1);

        service.updateTop(7L, 42L, true);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(cacheInvalidator).invalidate(42L);
        verify(postFeedService, never()).invalidateMyPublishedCache(7L);
    }

    @Test
    void publishInvalidatesPublicAndAuthorFeedsAfterCommit() {
        when(mapper.publish(42L, 7L)).thenReturn(1);
        when(mapper.findById(42L)).thenReturn(null);

        service.publish(7L, 42L);

        verify(cacheInvalidator, never()).invalidate(42L);
        verify(cacheInvalidator, never()).invalidateAllPublicFeedPages();
        verify(postFeedService, never()).invalidateMyPublishedCache(7L);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(cacheInvalidator).invalidate(42L);
        verify(cacheInvalidator).invalidateAllPublicFeedPages();
        verify(postFeedService).invalidateMyPublishedCache(7L);
    }
}
