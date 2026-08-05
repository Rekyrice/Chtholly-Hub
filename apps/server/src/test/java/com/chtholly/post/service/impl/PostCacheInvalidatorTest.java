package com.chtholly.post.service.impl;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostCacheInvalidatorTest {

    @Mock private StringRedisTemplate redis;
    @Mock private SetOperations<String, String> sets;
    @Mock private ZSetOperations<String, String> sortedSets;

    private Cache<String, PageResponse<FeedItemResponse>> feedCache;
    private Cache<String, PostDetailResponse> detailCache;
    private PostCacheInvalidator invalidator;

    @BeforeEach
    void setUp() {
        feedCache = Caffeine.newBuilder().build();
        detailCache = Caffeine.newBuilder().build();
        invalidator = new PostCacheInvalidator(redis, feedCache, detailCache);
    }

    @Test
    void invalidateDeletesIndexedOffsetAndCursorFragments() {
        long hour = System.currentTimeMillis() / 3_600_000L;
        String offsetPage = "feed:public:10:1:v3";
        String cursorPage = "feed:public:10:cursor-slot:v3";
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.members("feed:public:index:42:" + hour)).thenReturn(Set.of(offsetPage, cursorPage));
        when(sets.members("feed:public:index:42:" + (hour - 1))).thenReturn(Set.of());
        feedCache.put(offsetPage, PageResponse.offset(List.of(), 1, 10, 0L, false, null));
        feedCache.put(cursorPage, PageResponse.offset(List.of(), 0, 10, 0L, false, null));

        invalidator.invalidate(42L);

        assertThat(feedCache.getIfPresent(offsetPage)).isNull();
        assertThat(feedCache.getIfPresent(cursorPage)).isNull();
        verify(redis).delete("feed:item:42");
        verify(redis).delete(List.of(
                "feed:public:ids:10:" + hour + ":1",
                "feed:public:ids:10:" + hour + ":1:hasMore",
                "feed:public:ids:10:" + hour + ":1:nextCursor"));
        verify(redis).delete(List.of(
                "feed:public:ids:10:" + hour + ":cursor-slot",
                "feed:public:ids:10:" + hour + ":cursor-slot:hasMore",
                "feed:public:ids:10:" + hour + ":cursor-slot:nextCursor"));
    }

    @Test
    void invalidateAllPublicPagesDeletesCurrentAndPreviousHourFragments() {
        long hour = System.currentTimeMillis() / 3_600_000L;
        String pageKey = "feed:public:20:1:v3";
        when(redis.opsForZSet()).thenReturn(sortedSets);
        when(sortedSets.range("feed:public:pages", 0, -1)).thenReturn(Set.of(pageKey));
        feedCache.put(pageKey, PageResponse.offset(List.of(), 1, 20, 0L, false, null));

        invalidator.invalidateAllPublicFeedPages();

        assertThat(feedCache.getIfPresent(pageKey)).isNull();
        verify(redis).delete(List.of(
                "feed:public:ids:20:" + hour + ":1",
                "feed:public:ids:20:" + hour + ":1:hasMore",
                "feed:public:ids:20:" + hour + ":1:nextCursor"));
        verify(redis).delete(List.of(
                "feed:public:ids:20:" + (hour - 1) + ":1",
                "feed:public:ids:20:" + (hour - 1) + ":1:hasMore",
                "feed:public:ids:20:" + (hour - 1) + ":1:nextCursor"));
        verify(redis).delete("feed:public:pages");
    }

    @Test
    void invalidateAllPublicPagesClearsUnindexedLocalEntriesWhenRedisIndexIsEmpty() {
        String unindexedPage = "feed:public:20:unindexed:v3";
        when(redis.opsForZSet()).thenReturn(sortedSets);
        when(sortedSets.range("feed:public:pages", 0, -1)).thenReturn(Set.of());
        feedCache.put(unindexedPage, PageResponse.offset(List.of(), 1, 20, 0L, false, null));

        invalidator.invalidateAllPublicFeedPages();

        assertThat(feedCache.getIfPresent(unindexedPage)).isNull();
    }

    @Test
    void reverseIndexFailureConservativelyClearsLocalPublicFeed() {
        long hour = System.currentTimeMillis() / 3_600_000L;
        String unrelatedPage = "feed:public:20:unindexed:v3";
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.members("feed:public:index:42:" + hour))
                .thenThrow(new IllegalStateException("redis down"));
        when(sets.members("feed:public:index:42:" + (hour - 1))).thenReturn(Set.of());
        feedCache.put(unrelatedPage, PageResponse.offset(List.of(), 1, 20, 0L, false, null));

        invalidator.invalidate(42L);

        assertThat(feedCache.getIfPresent(unrelatedPage)).isNull();
    }

    @Test
    void strictPostInvalidationReportsRedisFailureAfterCompletingLocalCleanup() {
        long hour = System.currentTimeMillis() / 3_600_000L;
        String detailKey = PostDetailQueryService.cacheKey(42L);
        String pageKey = "feed:public:20:1:v3";
        when(redis.delete("feed:item:42")).thenThrow(new IllegalStateException("redis down"));
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.members("feed:public:index:42:" + hour)).thenReturn(Set.of(pageKey));
        when(sets.members("feed:public:index:42:" + (hour - 1))).thenReturn(Set.of());
        detailCache.put(detailKey, org.mockito.Mockito.mock(PostDetailResponse.class));
        feedCache.put(pageKey, PageResponse.offset(List.of(), 1, 20, 0L, false, null));

        assertThatThrownBy(() -> invalidator.invalidateStrict(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Strict post cache invalidation failed");

        assertThat(detailCache.getIfPresent(detailKey)).isNull();
        assertThat(feedCache.getIfPresent(pageKey)).isNull();
        verify(sets).members("feed:public:index:42:" + (hour - 1));
    }

    @Test
    void strictPublicFeedInvalidationReportsFailureAfterTryingRemainingRedisDeletes() {
        long hour = System.currentTimeMillis() / 3_600_000L;
        String pageKey = "feed:public:20:1:v3";
        List<String> currentFragments = List.of(
                "feed:public:ids:20:" + hour + ":1",
                "feed:public:ids:20:" + hour + ":1:hasMore",
                "feed:public:ids:20:" + hour + ":1:nextCursor");
        List<String> previousFragments = List.of(
                "feed:public:ids:20:" + (hour - 1) + ":1",
                "feed:public:ids:20:" + (hour - 1) + ":1:hasMore",
                "feed:public:ids:20:" + (hour - 1) + ":1:nextCursor");
        when(redis.opsForZSet()).thenReturn(sortedSets);
        when(sortedSets.range("feed:public:pages", 0, -1)).thenReturn(Set.of(pageKey));
        when(redis.delete(currentFragments)).thenThrow(new IllegalStateException("redis down"));
        feedCache.put(pageKey, PageResponse.offset(List.of(), 1, 20, 0L, false, null));

        assertThatThrownBy(invalidator::invalidateAllPublicFeedPagesStrict)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Strict public Feed cache invalidation failed");

        assertThat(feedCache.getIfPresent(pageKey)).isNull();
        verify(redis).delete(previousFragments);
        verify(redis).delete("feed:public:pages");
    }
}
