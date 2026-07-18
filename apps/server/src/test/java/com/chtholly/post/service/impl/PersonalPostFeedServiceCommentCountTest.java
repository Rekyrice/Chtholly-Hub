package com.chtholly.post.service.impl;

import com.chtholly.cache.hotkey.HotKeyDetector;
import com.chtholly.comment.service.CommentService;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.feed.FeedTimelineProperties;
import com.chtholly.post.feed.FeedTimelineService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostFeedRow;
import com.chtholly.user.service.PublicAuthorQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalPostFeedServiceCommentCountTest {

    @Mock private PostMapper postMapper;
    @Mock private StringRedisTemplate redis;
    @Mock private CounterService counterService;
    @Mock private CommentService commentService;
    @Mock private Cache<String, PageResponse<FeedItemResponse>> feedMineCache;
    @Mock private HotKeyDetector hotKey;
    @Mock private FeedTimelineService feedTimelineService;
    @Mock private FeedTimelineProperties feedTimelineProperties;
    @Mock private PublicAuthorQueryService publicAuthorQueryService;

    private PersonalPostFeedService service;

    @BeforeEach
    void setUp() {
        service = new PersonalPostFeedService(
                postMapper, redis, new ObjectMapper(), counterService, commentService,
                feedMineCache, hotKey, feedTimelineService, feedTimelineProperties,
                publicAuthorQueryService);
    }

    @Test
    void mineFeedRowMappingAddsCommentCountsInOneBatch() throws Exception {
        PostFeedRow row = row(101L);
        when(counterService.getCounts("post", "101", List.of("like", "fav"))).thenReturn(Map.of());
        when(commentService.countActiveByPostIds(List.of(101L))).thenReturn(Map.of(101L, 4L));
        Method method = PersonalPostFeedService.class.getDeclaredMethod(
                "mapRowsToItems", List.class, Long.class, boolean.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<FeedItemResponse> items = (List<FeedItemResponse>) method.invoke(
                service, List.of(row), null, true);

        assertThat(items.getFirst().commentCount()).isEqualTo(4L);
        verify(commentService).countActiveByPostIds(List.of(101L));
    }

    @Test
    void followingFeedBatchMappingAddsCommentCountsInOneBatch() throws Exception {
        PostFeedRow row = row(202L);
        when(counterService.getCountsBatch("post", List.of("202"), List.of("like", "fav")))
                .thenReturn(Map.of("202", Map.of()));
        when(counterService.batchIsLiked(9L, List.of(202L))).thenReturn(Map.of(202L, false));
        when(counterService.batchIsFaved(9L, List.of(202L))).thenReturn(Map.of(202L, false));
        when(commentService.countActiveByPostIds(List.of(202L))).thenReturn(Map.of(202L, 3L));
        Method method = PersonalPostFeedService.class.getDeclaredMethod(
                "mapRowsToItemsBatch", List.class, long.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<FeedItemResponse> items = (List<FeedItemResponse>) method.invoke(
                service, List.of(row), 9L);

        assertThat(items.getFirst().commentCount()).isEqualTo(3L);
        verify(commentService).countActiveByPostIds(List.of(202L));
    }

    private static PostFeedRow row(long id) {
        PostFeedRow row = new PostFeedRow();
        row.setId(id);
        row.setSlug("post-" + id);
        row.setTitle("title");
        row.setTags("[]");
        row.setImgUrls("[]");
        row.setAuthorId(7L);
        row.setAuthorNickname("Author");
        return row;
    }
}
