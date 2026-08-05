package com.chtholly.post.outbox;

import com.chtholly.counter.service.UserCounterService;
import com.chtholly.llm.rag.PostRagIndexer;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.post.feed.FeedTimelineService;
import com.chtholly.post.service.PostFeedService;
import com.chtholly.post.service.impl.PostCacheInvalidator;
import com.chtholly.search.index.SearchIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostOutboxProjectionServiceTest {

    @Mock private PostMapper postMapper;
    @Mock private PostCacheInvalidator cacheInvalidator;
    @Mock private PostFeedService postFeedService;
    @Mock private UserCounterService userCounterService;
    @Mock private PostRagIndexer ragIndexer;
    @Mock private SearchIndexService searchIndexService;
    @Mock private FeedTimelineService feedTimelineService;

    @Test
    void publishedEventRepairsCachesCounterProjectionAndRag() {
        Post published = post(42L, 7L, "published", "public");
        when(postMapper.findById(42L)).thenReturn(published);

        service().project("PostPublished", 42L);

        verify(cacheInvalidator).invalidateStrict(42L);
        verify(cacheInvalidator).invalidateAllPublicFeedPagesStrict();
        verify(postFeedService).invalidateMyPublishedCacheStrict(7L);
        verify(postFeedService).invalidateFollowingAuthorCacheStrict(7L);
        verify(userCounterService).invalidateReactionCounters(7L);
        verify(ragIndexer).ensureIndexed(42L);
        verify(searchIndexService).upsertPost(42L);
        verify(feedTimelineService).reconcilePost(42L, published);
    }

    @Test
    void deletedEventRemovesSearchRagAndTimelineProjections() {
        Post deleted = post(42L, 7L, "deleted", "public");
        when(postMapper.findById(42L)).thenReturn(deleted);

        service().project("PostDeleted", 42L);

        verify(cacheInvalidator).invalidateStrict(42L);
        verify(cacheInvalidator).invalidateAllPublicFeedPagesStrict();
        verify(userCounterService).invalidateReactionCounters(7L);
        verify(ragIndexer).ensureIndexed(42L);
        verify(searchIndexService).upsertPost(42L);
        verify(feedTimelineService).reconcilePost(42L, deleted);
    }

    @Test
    void visibilityEventRechecksWhetherThePostNowBelongsInRag() {
        when(postMapper.findById(42L)).thenReturn(Post.builder()
                .id(42L)
                .creatorId(7L)
                .status("published")
                .visible("public")
                .build());

        service().project("PostVisibilityChanged", 42L);

        verify(ragIndexer).ensureIndexed(42L);
        verify(searchIndexService).upsertPost(42L);
        verify(feedTimelineService).reconcilePost(eq(42L), any(Post.class));
    }

    @Test
    void metadataEventInvalidatesEveryPublicFeedPageBeforeRebuildingProjections() {
        Post updated = post(42L, 7L, "published", "public");
        when(postMapper.findById(42L)).thenReturn(updated);

        service().project("PostMetadataUpdated", 42L);

        verify(cacheInvalidator).invalidateAllPublicFeedPagesStrict();
        verify(feedTimelineService).reconcilePost(42L, updated);
    }

    @Test
    void topEventInvalidatesEveryPublicFeedPageBecauseOrderingMayHaveChanged() {
        when(postMapper.findById(42L)).thenReturn(post(42L, 7L, "published", "public"));

        service().project("PostTopChanged", 42L);

        verify(cacheInvalidator).invalidateAllPublicFeedPagesStrict();
    }

    @Test
    void searchFailureDoesNotSuppressRagOrFollowerTimelineProjection() {
        Post published = post(42L, 7L, "published", "public");
        when(postMapper.findById(42L)).thenReturn(published);
        doThrow(new IllegalStateException("search unavailable"))
                .when(searchIndexService).upsertPost(42L);

        assertThatThrownBy(() -> service().project("PostPublished", 42L))
                .isInstanceOf(IllegalStateException.class);

        verify(ragIndexer).ensureIndexed(42L);
        verify(feedTimelineService).reconcilePost(42L, published);
    }

    @Test
    void ragFailureDoesNotSuppressFollowerTimelineProjection() {
        Post published = post(42L, 7L, "published", "public");
        when(postMapper.findById(42L)).thenReturn(published);
        doThrow(new IllegalStateException("rag unavailable"))
                .when(ragIndexer).ensureIndexed(42L);

        assertThatThrownBy(() -> service().project("PostPublished", 42L))
                .isInstanceOf(IllegalStateException.class);

        verify(searchIndexService).upsertPost(42L);
        verify(feedTimelineService).reconcilePost(42L, published);
    }

    @Test
    void cacheFailureDoesNotSuppressAnyIndependentExternalProjection() {
        Post published = post(42L, 7L, "published", "public");
        when(postMapper.findById(42L)).thenReturn(published);
        doThrow(new IllegalStateException("cache unavailable"))
                .when(cacheInvalidator).invalidateStrict(42L);

        assertThatThrownBy(() -> service().project("PostPublished", 42L))
                .isInstanceOf(IllegalStateException.class);

        verify(searchIndexService).upsertPost(42L);
        verify(ragIndexer).ensureIndexed(42L);
        verify(feedTimelineService).reconcilePost(42L, published);
    }

    @Test
    void authorFeedCacheFailureKeepsTheDurableProjectionRetryable() {
        Post published = post(42L, 7L, "published", "public");
        when(postMapper.findById(42L)).thenReturn(published);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(postFeedService).invalidateMyPublishedCacheStrict(7L);

        assertThatThrownBy(() -> service().project("PostPublished", 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("author-feed-cache");

        verify(searchIndexService).upsertPost(42L);
        verify(ragIndexer).ensureIndexed(42L);
        verify(feedTimelineService).reconcilePost(42L, published);
    }

    @Test
    void unknownPostEventIsIgnoredWithoutReadingTheAggregate() {
        service().project("PostUnknown", 42L);

        verifyNoInteractions(
                postMapper,
                cacheInvalidator,
                postFeedService,
                userCounterService,
                ragIndexer,
                searchIndexService,
                feedTimelineService);
    }

    private PostOutboxProjectionService service() {
        return new PostOutboxProjectionService(
                postMapper,
                cacheInvalidator,
                postFeedService,
                userCounterService,
                ragIndexer,
                searchIndexService,
                feedTimelineService);
    }

    private static Post post(long id, long creatorId, String status, String visible) {
        return Post.builder()
                .id(id)
                .creatorId(creatorId)
                .status(status)
                .visible(visible)
                .build();
    }
}
