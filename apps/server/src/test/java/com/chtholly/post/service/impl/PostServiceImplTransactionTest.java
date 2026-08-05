package com.chtholly.post.service.impl;

import com.chtholly.counter.service.UserCounterService;
import com.chtholly.llm.rag.PostRagIndexer;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.post.outbox.PostOutboxProjectionProcessor;
import com.chtholly.post.service.PostFeedService;
import com.chtholly.relation.outbox.OutboxMapper;
import com.chtholly.search.index.SearchIndexService;
import com.chtholly.storage.StorageService;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
    @Mock private PostOutboxProjectionProcessor projectionProcessor;
    @Mock private StorageService storageService;

    private PostServiceImpl service;

    private static Post draftOwnedBy(long postId, long creatorId) {
        return Post.builder()
                .id(postId)
                .creatorId(creatorId)
                .status("draft")
                .visible("public")
                .tags("[]")
                .build();
    }

    private static Post publishedOwnedBy(long postId, long creatorId) {
        return Post.builder()
                .id(postId)
                .creatorId(creatorId)
                .status("published")
                .visible("public")
                .tags("[]")
                .build();
    }

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        lenient().when(outboxMapper.insert(anyLong(), anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(1);
        SnowflakeIdGenerator idGenerator = new SnowflakeIdGenerator();
        ObjectMapper objectMapper = new ObjectMapper();
        PostPayloadCodec payloadCodec = new PostPayloadCodec(objectMapper);
        PostOutboxWriter outboxWriter = new PostOutboxWriter(outboxMapper, objectMapper, idGenerator);
        PostCommittedSideEffectCoordinator sideEffectCoordinator =
                new PostCommittedSideEffectCoordinator(projectionProcessor, eventPublisher);
        PostMutationCacheCoordinator cacheCoordinator =
                new PostMutationCacheCoordinator(cacheInvalidator, postFeedService);
        service = new PostServiceImpl(
                new PostDraftCommandService(
                        mapper, idGenerator, storageService, outboxWriter,
                        sideEffectCoordinator, cacheCoordinator),
                new PostMetadataCommandService(
                        mapper, payloadCodec, tagService, outboxWriter,
                        sideEffectCoordinator, cacheCoordinator),
                new PostPublicationCommandService(
                        mapper, payloadCodec, tagService, outboxWriter,
                        sideEffectCoordinator, cacheCoordinator),
                new PostDeletionCommandService(
                        mapper, payloadCodec, tagService, outboxWriter,
                        sideEffectCoordinator, cacheCoordinator),
                detailQueryService,
                backgroundQueryService);
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
        when(mapper.findByIdForUpdate(42L)).thenReturn(publishedOwnedBy(42L, 7L));
        when(mapper.updateTop(42L, 7L, true)).thenReturn(1);

        service.updateTop(7L, 42L, true);

        verify(cacheInvalidator).invalidate(42L);
        verify(postFeedService, never()).invalidateMyPublishedCache(7L);
        verify(postFeedService, never()).invalidateFollowingAuthorCacheStrict(7L);
        verify(postFeedService, never()).invalidateFollowingAuthorCache(7L);
        verify(cacheInvalidator, never()).invalidateAllPublicFeedPages();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isNotEmpty();

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(cacheInvalidator, times(2)).invalidate(42L);
        verify(postFeedService).invalidateMyPublishedCache(7L);
        verify(postFeedService).invalidateFollowingAuthorCache(7L);
        verify(cacheInvalidator, never()).invalidateAllPublicFeedPages();
    }

    @Test
    void rollbackDoesNotRunAfterCommitInvalidation() {
        when(mapper.findByIdForUpdate(42L)).thenReturn(publishedOwnedBy(42L, 7L));
        when(mapper.updateTop(42L, 7L, true)).thenReturn(1);

        service.updateTop(7L, 42L, true);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(cacheInvalidator).invalidate(42L);
        verify(postFeedService, never()).invalidateMyPublishedCache(7L);
        verify(postFeedService, never()).invalidateFollowingAuthorCacheStrict(7L);
        verify(postFeedService, never()).invalidateFollowingAuthorCache(7L);
    }

    @Test
    void privateVisibilityChangeInvalidatesAuthorFeedOnlyAfterCommit() {
        when(mapper.findByIdForUpdate(42L)).thenReturn(publishedOwnedBy(42L, 7L));
        when(mapper.updateVisibility(42L, 7L, "private")).thenReturn(1);

        service.updateVisibility(7L, 42L, "private");

        verify(postFeedService, never()).invalidateFollowingAuthorCacheStrict(7L);
        verify(postFeedService, never()).invalidateFollowingAuthorCache(7L);
        verify(cacheInvalidator, never()).invalidateAllPublicFeedPages();

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(postFeedService, never()).invalidateFollowingAuthorCacheStrict(7L);
        verify(postFeedService).invalidateFollowingAuthorCache(7L);
        verify(cacheInvalidator).invalidateAllPublicFeedPages();
    }

    @Test
    void visibilityChangePersistsWhenAuthorFeedCacheIsUnavailable() {
        when(mapper.findByIdForUpdate(42L)).thenReturn(publishedOwnedBy(42L, 7L));
        when(mapper.updateVisibility(42L, 7L, "private")).thenReturn(1);
        lenient().doThrow(new IllegalStateException("redis down"))
                .when(postFeedService)
                .invalidateFollowingAuthorCacheStrict(7L);

        assertThatCode(() -> service.updateVisibility(7L, 42L, "private"))
                .doesNotThrowAnyException();

        verify(mapper).updateVisibility(42L, 7L, "private");
        verify(postFeedService, never()).invalidateFollowingAuthorCacheStrict(7L);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isNotEmpty();
    }

    @Test
    void publishInvalidatesPublicAndAuthorFeedsAfterCommit() {
        when(mapper.findDraftByIdForUpdate(42L)).thenReturn(draftOwnedBy(42L, 7L));
        when(mapper.publish(42L, 7L)).thenReturn(1);
        when(mapper.findById(42L)).thenReturn(null);
        lenient().doThrow(new IllegalStateException("redis down"))
                .when(postFeedService)
                .invalidateFollowingAuthorCacheStrict(7L);

        service.publish(7L, 42L);

        verify(cacheInvalidator, never()).invalidate(42L);
        verify(cacheInvalidator, never()).invalidateAllPublicFeedPages();
        verify(postFeedService, never()).invalidateMyPublishedCache(7L);
        verify(postFeedService, never()).invalidateFollowingAuthorCacheStrict(7L);
        verify(postFeedService, never()).invalidateFollowingAuthorCache(7L);
        verify(userCounterService, never()).invalidateReactionCounters(7L);
        verify(searchIndexService, never()).upsertPost(42L);
        verify(ragIndexService, never()).ensureIndexed(42L);
        verify(projectionProcessor, never()).process(anyLong(), eq("PostPublished"), eq(42L));

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(cacheInvalidator).invalidate(42L);
        verify(cacheInvalidator).invalidateAllPublicFeedPages();
        verify(postFeedService).invalidateMyPublishedCache(7L);
        verify(postFeedService).invalidateFollowingAuthorCache(7L);
        verify(projectionProcessor).process(anyLong(), eq("PostPublished"), eq(42L));
    }

    @Test
    void deletionPersistsWhenAuthorFeedCacheIsUnavailable() {
        when(mapper.findByIdForUpdate(43L)).thenReturn(publishedOwnedBy(43L, 7L));
        when(mapper.softDelete(43L, 7L)).thenReturn(1);
        lenient().doThrow(new IllegalStateException("redis down"))
                .when(postFeedService)
                .invalidateFollowingAuthorCacheStrict(7L);

        assertThatCode(() -> service.delete(7L, 43L))
                .doesNotThrowAnyException();

        verify(mapper).softDelete(43L, 7L);
        verify(outboxMapper).insert(
                anyLong(), eq("post"), eq(43L), eq("PostDeleted"), anyString());
        verify(postFeedService, never()).invalidateFollowingAuthorCacheStrict(7L);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isNotEmpty();
    }

    @Test
    void outboxFailurePreventsPublicationSideEffectsFromBeingScheduled() {
        when(mapper.findDraftByIdForUpdate(42L)).thenReturn(draftOwnedBy(42L, 7L));
        when(mapper.publish(42L, 7L)).thenReturn(1);
        when(mapper.findById(42L)).thenReturn(null);
        doThrow(new IllegalStateException("outbox down"))
                .when(outboxMapper)
                .insert(anyLong(), eq("post"), eq(42L), eq("PostPublished"), anyString());

        assertThatThrownBy(() -> service.publish(7L, 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox down");

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(userCounterService, never()).invalidateReactionCounters(7L);
        verify(searchIndexService, never()).upsertPost(42L);
        verify(ragIndexService, never()).ensureIndexed(42L);
        verify(projectionProcessor, never()).process(anyLong(), eq("PostPublished"), eq(42L));
    }
}
