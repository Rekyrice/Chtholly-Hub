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
}
