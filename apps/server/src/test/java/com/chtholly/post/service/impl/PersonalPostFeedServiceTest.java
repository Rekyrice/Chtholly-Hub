package com.chtholly.post.service.impl;

import com.chtholly.cache.hotkey.HotKeyDetector;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.feed.FeedTimelineProperties;
import com.chtholly.post.feed.FeedTimelineService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.user.model.PublicAuthorSnapshot;
import com.chtholly.user.service.PublicAuthorQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonalPostFeedServiceTest {

    @Test
    void givenLocalMineCacheWithOldAuthor_whenRead_thenOverlaysCurrentPublicProfile() {
        PostMapper mapper = mock(PostMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CounterService counterService = mock(CounterService.class);
        HotKeyDetector hotKey = mock(HotKeyDetector.class);
        PublicAuthorQueryService authorQuery = mock(PublicAuthorQueryService.class);
        com.github.benmanes.caffeine.cache.Cache<String, PageResponse<FeedItemResponse>> cache =
                Caffeine.newBuilder().build();
        FeedItemResponse stale = new FeedItemResponse(
                "99", "post", "标题", "摘要", null, List.of(), null, "old_handle", "/old.webp", "旧昵称",
                "[]", 3L, 1L, false, false, true, Instant.parse("2026-07-01T00:00:00Z"));
        cache.put("feed:mine:42:10:1", PageResponse.offset(List.of(stale), 1, 10, 1L));
        when(hotKey.ttlForMine(30, "feed:mine:42:10:1")).thenReturn(30);
        when(redis.getExpire("feed:mine:42:10:1")).thenReturn(30L);
        when(counterService.batchIsLiked(42L, List.of(99L))).thenReturn(Map.of(99L, false));
        when(counterService.batchIsFaved(42L, List.of(99L))).thenReturn(Map.of(99L, false));
        when(authorQuery.findByIds(List.of(42L))).thenReturn(Map.of(42L, new PublicAuthorSnapshot(
                42L, "rekyrice", "Rekyrice", "/new.webp", "简介", "[\"动画\"]",
                Instant.parse("2026-02-01T00:00:00Z"))));
        PersonalPostFeedService service = new PersonalPostFeedService(
                mapper, redis, new ObjectMapper(), counterService, cache, hotKey,
                mock(FeedTimelineService.class), mock(FeedTimelineProperties.class), authorQuery);

        FeedItemResponse result = service.getMyPublished(42L, 1, 10).items().getFirst();

        assertThat(result.authorHandle()).isEqualTo("rekyrice");
        assertThat(result.authorNickname()).isEqualTo("Rekyrice");
        assertThat(result.authorAvatar()).isEqualTo("/new.webp");
        assertThat(result.tagJson()).isEqualTo("[\"动画\"]");
    }
}
