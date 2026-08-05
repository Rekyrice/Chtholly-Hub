package com.chtholly.post.service.impl;

import com.chtholly.cache.hotkey.HotKeyDetector;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.counter.service.CounterService;
import com.chtholly.comment.service.CommentService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.feed.FeedTimelineProperties;
import com.chtholly.post.feed.FeedTimelineService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostFeedRow;
import com.chtholly.user.model.PublicAuthorSnapshot;
import com.chtholly.user.service.PublicAuthorQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalPostFeedServiceTest {

    @Test
    void followingFeedScansPastFortyOneUnauthorizedTimelineCandidates() {
        PostMapper mapper = mock(PostMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        FeedTimelineService timeline = mock(FeedTimelineService.class);
        FeedItemAssembler assembler = mock(FeedItemAssembler.class);
        List<Long> firstRankBatch = new ArrayList<>(
                LongStream.rangeClosed(1L, 41L).boxed().toList());
        firstRankBatch.add(142L);
        when(timeline.getTimelinePostIdBatch(9L, 0L, 50))
                .thenReturn(new FeedTimelineService.TimelineCandidateBatch(firstRankBatch, 42, true));
        when(timeline.getFollowedBigVAuthors(9L)).thenReturn(List.of());
        when(mapper.listFollowingFeedRowsByIds(firstRankBatch, 9L))
                .thenReturn(List.of(feedRow(142L, 8L)));
        when(mapper.listFollowingFeedAuthoritative(9L, 11, 0))
                .thenReturn(List.of(feedRow(142L, 8L)));
        when(assembler.fromRowsBatch(anyList(), eq(9L))).thenReturn(List.of());
        PersonalPostFeedService service = serviceWithFollowing(
                mapper, redis, new ObjectMapper(), timeline,
                new FeedTimelineProperties(), assembler);

        service.getFollowingFeed(9L, 1, 10);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<PostFeedRow>> rows =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(assembler).fromRowsBatch(rows.capture(), eq(9L));
        assertThat(rows.getValue()).extracting(PostFeedRow::getId).containsExactly(142L);
    }

    @Test
    void followingFeedFallsBackToAuthoritativeMysqlAfterBoundedTimelineScan() {
        PostMapper mapper = mock(PostMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        FeedTimelineService timeline = mock(FeedTimelineService.class);
        FeedItemAssembler assembler = mock(FeedItemAssembler.class);
        when(timeline.getFollowedBigVAuthors(9L)).thenReturn(List.of());
        when(timeline.getTimelinePostIdBatch(eq(9L), anyLong(), eq(50)))
                .thenAnswer(invocation -> {
                    long start = invocation.getArgument(1);
                    List<Long> ids = LongStream.rangeClosed(start + 1L, start + 50L)
                            .boxed()
                            .toList();
                    return new FeedTimelineService.TimelineCandidateBatch(ids, 50, false);
                });
        when(mapper.listFollowingFeedRowsByIds(anyList(), eq(9L))).thenReturn(List.of());
        when(mapper.listFollowingFeedAuthoritative(9L, 11, 0))
                .thenReturn(List.of(feedRow(2_001L, 8L)));
        when(assembler.fromRowsBatch(anyList(), eq(9L))).thenReturn(List.of());
        PersonalPostFeedService service = serviceWithFollowing(
                mapper, redis, new ObjectMapper(), timeline,
                new FeedTimelineProperties(), assembler);

        service.getFollowingFeed(9L, 1, 10);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<PostFeedRow>> rows =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(assembler).fromRowsBatch(rows.capture(), eq(9L));
        assertThat(rows.getValue()).extracting(PostFeedRow::getId).containsExactly(2_001L);
        verify(timeline, times(20)).getTimelinePostIdBatch(eq(9L), anyLong(), eq(50));
        verify(mapper).listFollowingFeedAuthoritative(9L, 11, 0);
    }

    @Test
    void followingFeedDefersRejectedCleanupUntilRankScanningCompletes() {
        PostMapper mapper = mock(PostMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        FeedTimelineService timeline = mock(FeedTimelineService.class);
        FeedItemAssembler assembler = mock(FeedItemAssembler.class);
        List<Long> firstBatch = LongStream.rangeClosed(1L, 50L).boxed().toList();
        List<Long> secondBatch = List.of(51L, 52L);
        AtomicBoolean cleanupStarted = new AtomicBoolean();
        doAnswer(ignored -> {
            cleanupStarted.set(true);
            return null;
        }).when(timeline).removeTimelinePostIds(eq(9L), anyList());
        when(timeline.getFollowedBigVAuthors(9L)).thenReturn(List.of());
        when(timeline.getTimelinePostIdBatch(9L, 0L, 50))
                .thenReturn(new FeedTimelineService.TimelineCandidateBatch(firstBatch, 50, false));
        when(timeline.getTimelinePostIdBatch(9L, 50L, 50))
                .thenAnswer(ignored -> cleanupStarted.get()
                        ? new FeedTimelineService.TimelineCandidateBatch(List.of(), 0, true)
                        : new FeedTimelineService.TimelineCandidateBatch(secondBatch, 2, true));
        PostFeedRow first = feedRow(1L, 8L);
        first.setPublishTime(Instant.parse("2026-08-05T03:00:00Z"));
        PostFeedRow second = feedRow(51L, 8L);
        second.setPublishTime(Instant.parse("2026-08-05T02:00:00Z"));
        PostFeedRow lookAhead = feedRow(52L, 8L);
        lookAhead.setPublishTime(Instant.parse("2026-08-05T01:00:00Z"));
        when(mapper.listFollowingFeedRowsByIds(firstBatch, 9L)).thenReturn(List.of(first));
        when(mapper.listFollowingFeedRowsByIds(secondBatch, 9L))
                .thenReturn(List.of(second, lookAhead));
        when(assembler.fromRowsBatch(anyList(), eq(9L))).thenReturn(List.of());
        PersonalPostFeedService service = serviceWithFollowing(
                mapper, redis, new ObjectMapper(), timeline,
                new FeedTimelineProperties(), assembler);

        service.getFollowingFeed(9L, 1, 2);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<PostFeedRow>> rows =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(assembler).fromRowsBatch(rows.capture(), eq(9L));
        assertThat(rows.getValue()).extracting(PostFeedRow::getId).containsExactly(1L, 51L);
        verify(timeline).removeTimelinePostIds(eq(9L), anyList());
    }

    @Test
    void followingFeedUsesMysqlWhenExhaustedProjectionCannotProveACompletePage() {
        PostMapper mapper = mock(PostMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        FeedTimelineService timeline = mock(FeedTimelineService.class);
        FeedItemAssembler assembler = mock(FeedItemAssembler.class);
        when(timeline.getFollowedBigVAuthors(9L)).thenReturn(List.of());
        when(timeline.getTimelinePostIdBatch(9L, 0L, 50))
                .thenReturn(new FeedTimelineService.TimelineCandidateBatch(List.of(1L), 1, true));
        when(mapper.listFollowingFeedRowsByIds(List.of(1L), 9L))
                .thenReturn(List.of(feedRow(1L, 8L)));
        when(mapper.listFollowingFeedAuthoritative(9L, 3, 0))
                .thenReturn(List.of(
                        feedRow(101L, 8L),
                        feedRow(102L, 8L),
                        feedRow(103L, 8L)));
        when(assembler.fromRowsBatch(anyList(), eq(9L))).thenReturn(List.of());
        PersonalPostFeedService service = serviceWithFollowing(
                mapper, redis, new ObjectMapper(), timeline,
                new FeedTimelineProperties(), assembler);

        service.getFollowingFeed(9L, 1, 2);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<PostFeedRow>> rows =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(assembler).fromRowsBatch(rows.capture(), eq(9L));
        assertThat(rows.getValue()).extracting(PostFeedRow::getId).containsExactly(101L, 102L);
        verify(mapper).listFollowingFeedAuthoritative(9L, 3, 0);
    }

    @Test
    void followingFeedExcludesStalePublicCandidateAfterUnfollow() {
        PostMapper mapper = mock(PostMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        FeedTimelineService timeline = mock(FeedTimelineService.class);
        FeedItemAssembler assembler = mock(FeedItemAssembler.class);
        PostFeedRow stalePublicCandidate = feedRow(101L, 7L);
        when(timeline.getTimelinePostIdBatch(9L, 0L, 50))
                .thenReturn(new FeedTimelineService.TimelineCandidateBatch(List.of(101L), 1, true));
        when(timeline.getFollowedBigVAuthors(9L)).thenReturn(List.of());
        when(mapper.listFollowingFeedRowsByIds(List.of(101L), 9L)).thenReturn(List.of());
        when(assembler.fromRowsBatch(anyList(), eq(9L))).thenReturn(List.of());
        PersonalPostFeedService service = serviceWithFollowing(
                mapper, redis, new ObjectMapper(), timeline,
                new FeedTimelineProperties(), assembler);

        service.getFollowingFeed(9L, 1, 10);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<PostFeedRow>> rows =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(assembler).fromRowsBatch(rows.capture(), eq(9L));
        assertThat(rows.getValue()).isEmpty();
        verify(mapper).listFollowingFeedRowsByIds(List.of(stalePublicCandidate.getId()), 9L);
    }

    @Test
    void followingFeedReauthorizesCachedBigVCandidatesAgainstMysql() throws Exception {
        PostMapper mapper = mock(PostMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        FeedTimelineService timeline = mock(FeedTimelineService.class);
        when(timeline.getTimelinePostIdBatch(9L, 0L, 50))
                .thenReturn(new FeedTimelineService.TimelineCandidateBatch(List.of(), 0, true));
        when(timeline.getFollowedBigVAuthors(9L)).thenReturn(List.of(7L));
        ObjectMapper objectMapper = new ObjectMapper();
        when(values.get(FollowingAuthorPostCache.cacheKey(7L)))
                .thenReturn(objectMapper.writeValueAsString(List.of(feedRow(102L, 7L))));
        when(mapper.listFollowingFeedRowsByIds(List.of(102L), 9L)).thenReturn(List.of());
        FeedItemAssembler assembler = mock(FeedItemAssembler.class);
        when(assembler.fromRowsBatch(anyList(), eq(9L))).thenReturn(List.of());
        PersonalPostFeedService service = serviceWithFollowing(
                mapper, redis, objectMapper, timeline,
                new FeedTimelineProperties(), assembler);

        service.getFollowingFeed(9L, 1, 10);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<PostFeedRow>> rows =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(assembler).fromRowsBatch(rows.capture(), eq(9L));
        assertThat(rows.getValue()).isEmpty();
        verify(mapper).listFollowingFeedRowsByIds(List.of(102L), 9L);
    }

    @Test
    void followingFeedFallsBackToMysqlWhenBigVAuthorSetReadFails() {
        PostMapper mapper = mock(PostMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        FeedTimelineService timeline = mock(FeedTimelineService.class);
        FeedItemAssembler assembler = mock(FeedItemAssembler.class);
        when(timeline.getFollowedBigVAuthors(9L))
                .thenThrow(new IllegalStateException("redis set unavailable"));
        when(mapper.listFollowingFeedAuthoritative(9L, 3, 0))
                .thenReturn(List.of(feedRow(301L, 8L)));
        when(assembler.fromRowsBatch(anyList(), eq(9L))).thenReturn(List.of());
        PersonalPostFeedService service = serviceWithFollowing(
                mapper, redis, new ObjectMapper(), timeline,
                new FeedTimelineProperties(), assembler);

        service.getFollowingFeed(9L, 1, 2);

        verify(mapper).listFollowingFeedAuthoritative(9L, 3, 0);
    }

    @Test
    void followingFeedFallsBackToMysqlWhenBigVPostCacheReadFails() {
        PostMapper mapper = mock(PostMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(FollowingAuthorPostCache.cacheKey(7L)))
                .thenThrow(new IllegalStateException("redis cache unavailable"));
        FeedTimelineService timeline = mock(FeedTimelineService.class);
        when(timeline.getFollowedBigVAuthors(9L)).thenReturn(List.of(7L));
        when(mapper.listFollowingFeedAuthoritative(9L, 3, 0))
                .thenReturn(List.of(feedRow(302L, 8L)));
        FeedItemAssembler assembler = mock(FeedItemAssembler.class);
        when(assembler.fromRowsBatch(anyList(), eq(9L))).thenReturn(List.of());
        PersonalPostFeedService service = serviceWithFollowing(
                mapper, redis, new ObjectMapper(), timeline,
                new FeedTimelineProperties(), assembler);

        service.getFollowingFeed(9L, 1, 2);

        verify(mapper).listFollowingFeedAuthoritative(9L, 3, 0);
    }

    @Test
    void followingFeedFallsBackToMysqlWhenTimelineReadFails() {
        PostMapper mapper = mock(PostMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        FeedTimelineService timeline = mock(FeedTimelineService.class);
        when(timeline.getFollowedBigVAuthors(9L)).thenReturn(List.of());
        when(timeline.getTimelinePostIdBatch(9L, 0L, 50))
                .thenThrow(new IllegalStateException("redis timeline unavailable"));
        when(mapper.listFollowingFeedAuthoritative(9L, 3, 0))
                .thenReturn(List.of(feedRow(303L, 8L)));
        FeedItemAssembler assembler = mock(FeedItemAssembler.class);
        when(assembler.fromRowsBatch(anyList(), eq(9L))).thenReturn(List.of());
        PersonalPostFeedService service = serviceWithFollowing(
                mapper, redis, new ObjectMapper(), timeline,
                new FeedTimelineProperties(), assembler);

        service.getFollowingFeed(9L, 1, 2);

        verify(mapper).listFollowingFeedAuthoritative(9L, 3, 0);
    }

    @Test
    void followingFeedDoesNotSwallowMysqlFallbackFailure() {
        PostMapper mapper = mock(PostMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        FeedTimelineService timeline = mock(FeedTimelineService.class);
        when(timeline.getFollowedBigVAuthors(9L))
                .thenThrow(new IllegalStateException("redis set unavailable"));
        when(mapper.listFollowingFeedAuthoritative(9L, 3, 0))
                .thenThrow(new IllegalStateException("mysql unavailable"));
        PersonalPostFeedService service = serviceWithFollowing(
                mapper, redis, new ObjectMapper(), timeline,
                new FeedTimelineProperties(), mock(FeedItemAssembler.class));

        assertThatThrownBy(() -> service.getFollowingFeed(9L, 1, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql unavailable");
    }

    @Test
    void followingOperationsDelegateToExtractedQueryService() {
        FollowingPostFeedQueryService following = mock(FollowingPostFeedQueryService.class);
        PageResponse<FeedItemResponse> expected = PageResponse.offset(List.of(), 2, 5, 0L);
        when(following.getFollowingFeed(42L, 2, 5)).thenReturn(expected);
        PersonalPostFeedService service = new PersonalPostFeedService(
                mock(PostMapper.class), mock(StringRedisTemplate.class), new ObjectMapper(),
                Caffeine.newBuilder().build(), mock(HotKeyDetector.class),
                mock(FeedItemAssembler.class), following);

        assertThat(service.getFollowingFeed(42L, 2, 5)).isSameAs(expected);
        service.invalidateFollowingAuthorCache(42L);
        service.invalidateFollowingAuthorCacheStrict(42L);

        verify(following).getFollowingFeed(42L, 2, 5);
        verify(following).invalidateAuthorCache(42L);
        verify(following).invalidateAuthorCacheStrict(42L);
    }

    @Test
    void invalidatingAuthorFeedDropsBigVPullCache() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        PersonalPostFeedService service = serviceWith(redis);

        service.invalidateFollowingAuthorCache(42L);

        verify(redis).delete("feed:bigv:posts:42");
    }

    @Test
    void strictAuthorFeedInvalidationPropagatesRedisFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        PersonalPostFeedService service = serviceWith(redis);
        doThrow(new IllegalStateException("redis down"))
                .when(redis)
                .delete("feed:bigv:posts:42");

        assertThatThrownBy(() -> service.invalidateFollowingAuthorCacheStrict(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis down");
    }

    @Test
    void strictPersonalFeedInvalidationPropagatesRedisScanFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        PersonalPostFeedService service = serviceWith(redis);
        when(redis.scan(any(ScanOptions.class)))
                .thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> service.invalidateMyPublishedCacheStrict(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis down");
    }

    @Test
    void givenLocalMineCacheWithOldAuthor_whenRead_thenOverlaysCurrentPublicProfile() {
        PostMapper mapper = mock(PostMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CounterService counterService = mock(CounterService.class);
        CommentService commentService = mock(CommentService.class);
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
        when(commentService.countActiveByPostIds(List.of(99L))).thenReturn(Map.of(99L, 6L));
        when(authorQuery.findByIds(List.of(42L))).thenReturn(Map.of(42L, new PublicAuthorSnapshot(
                42L, "rekyrice", "Rekyrice", "/new.webp", "简介", "[\"动画\"]",
                Instant.parse("2026-02-01T00:00:00Z"))));
        FeedItemAssembler assembler = new FeedItemAssembler(counterService, commentService, authorQuery);
        PersonalPostFeedService service = new PersonalPostFeedService(
                mapper, redis, new ObjectMapper(), cache, hotKey,
                assembler, mock(FollowingPostFeedQueryService.class));

        FeedItemResponse result = service.getMyPublished(42L, 1, 10).items().getFirst();

        assertThat(result.authorHandle()).isEqualTo("rekyrice");
        assertThat(result.authorNickname()).isEqualTo("Rekyrice");
        assertThat(result.authorAvatar()).isEqualTo("/new.webp");
        assertThat(result.commentCount()).isEqualTo(6L);
        assertThat(result.tagJson()).isEqualTo("[\"动画\"]");
    }

    private PersonalPostFeedService serviceWith(StringRedisTemplate redis) {
        CounterService counterService = mock(CounterService.class);
        CommentService commentService = mock(CommentService.class);
        PublicAuthorQueryService authorQuery = mock(PublicAuthorQueryService.class);
        FeedItemAssembler assembler = new FeedItemAssembler(counterService, commentService, authorQuery);
        return serviceWithFollowing(
                mock(PostMapper.class), redis, new ObjectMapper(),
                mock(FeedTimelineService.class), new FeedTimelineProperties(), assembler);
    }

    private PersonalPostFeedService serviceWithFollowing(
            PostMapper mapper,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            FeedTimelineService timeline,
            FeedTimelineProperties properties,
            FeedItemAssembler assembler) {
        FollowingAuthorPostCache authorPostCache = new FollowingAuthorPostCache(
                mapper, redis, objectMapper, properties);
        FollowingPostFeedQueryService followingFeedQueryService =
                new FollowingPostFeedQueryService(
                        mapper, timeline, authorPostCache, assembler);
        return new PersonalPostFeedService(
                mapper,
                redis,
                objectMapper,
                Caffeine.newBuilder().build(),
                mock(HotKeyDetector.class),
                assembler,
                followingFeedQueryService);
    }

    private static PostFeedRow feedRow(long postId, long authorId) {
        PostFeedRow row = new PostFeedRow();
        row.setId(postId);
        row.setAuthorId(authorId);
        row.setTitle("stale followers candidate");
        return row;
    }
}
