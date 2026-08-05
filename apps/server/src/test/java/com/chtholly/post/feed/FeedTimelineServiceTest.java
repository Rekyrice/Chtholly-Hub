package com.chtholly.post.feed;

import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.post.model.PostFeedRow;
import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.service.RelationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedTimelineServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private RelationMapper relationMapper;
    @Mock
    private RelationService relationService;
    @Mock
    private PostMapper postMapper;
    @Mock
    private ZSetOperations<String, String> zSetOps;
    @Mock
    private SetOperations<String, String> setOps;

    private FeedTimelineProperties properties;
    private FeedTimelineService service;

    @BeforeEach
    void setUp() {
        properties = new FeedTimelineProperties();
        properties.getBigv().setThreshold(1000);
        service = new FeedTimelineService(redis, properties, relationMapper, relationService, postMapper);
        lenient().when(redis.opsForZSet()).thenReturn(zSetOps);
        lenient().when(redis.opsForSet()).thenReturn(setOps);
    }

    @Test
    void given_smallAuthor_when_onPostPublished_then_pushesToFollowers() {
        when(relationMapper.listActiveFollowerIdsByTarget(10L, 1000))
                .thenReturn(List.of(100L, 101L));

        Instant publishTime = Instant.parse("2026-06-23T10:00:00Z");
        service.onPostPublished(10L, 999L, publishTime);

        verify(zSetOps, times(2)).add(anyString(), eq("999"), eq((double) publishTime.toEpochMilli()));
        verify(setOps, never()).add(eq(FeedTimelineService.BIGV_AUTHORS_KEY), anyString());
    }

    @Test
    void given_bigVAuthor_when_onPostPublished_then_marksBigVWithoutPush() {
        properties.getBigv().setThreshold(2);
        when(relationMapper.listActiveFollowerIdsByTarget(10L, 2))
                .thenReturn(List.of(100L, 101L));

        service.onPostPublished(10L, 999L, Instant.now());

        verify(setOps).add(FeedTimelineService.BIGV_AUTHORS_KEY, "10");
        verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void given_authorLeavesBigVMode_when_onPostPublished_then_backfillsBeforeRemovingMarker() {
        Instant older = Instant.parse("2026-06-22T10:00:00Z");
        Instant newest = Instant.parse("2026-06-23T10:00:00Z");
        when(relationMapper.listActiveFollowerIdsByTarget(10L, 1000))
                .thenReturn(List.of(100L, 101L));
        when(setOps.isMember(FeedTimelineService.BIGV_AUTHORS_KEY, "10")).thenReturn(true);
        when(postMapper.listRecentPublicByCreators(eq(List.of(10L)), any(Instant.class), eq(10_000)))
                .thenReturn(List.of(feedRow(998L, older), feedRow(999L, newest)));

        service.onPostPublished(10L, 999L, newest);

        InOrder order = org.mockito.Mockito.inOrder(zSetOps, setOps);
        order.verify(zSetOps).add("feed:timeline:100", "998", (double) older.toEpochMilli());
        order.verify(zSetOps).add("feed:timeline:100", "999", (double) newest.toEpochMilli());
        order.verify(zSetOps).add("feed:timeline:101", "998", (double) older.toEpochMilli());
        order.verify(zSetOps).add("feed:timeline:101", "999", (double) newest.toEpochMilli());
        order.verify(setOps).remove(FeedTimelineService.BIGV_AUTHORS_KEY, "10");
    }

    @Test
    void given_bigVBackfillFails_when_onPostPublished_then_keepsPullModeMarker() {
        when(relationMapper.listActiveFollowerIdsByTarget(10L, 1000))
                .thenReturn(List.of(100L));
        when(setOps.isMember(FeedTimelineService.BIGV_AUTHORS_KEY, "10")).thenReturn(true);
        when(postMapper.listRecentPublicByCreators(eq(List.of(10L)), any(Instant.class), eq(10_000)))
                .thenThrow(new IllegalStateException("database unavailable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.onPostPublished(10L, 999L, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        verify(setOps, never()).remove(FeedTimelineService.BIGV_AUTHORS_KEY, "10");
    }

    @Test
    void reconcilePublicPublishedPostPushesIdempotentlyAndClearsStaleBigVMark() {
        when(relationMapper.listActiveFollowerIdsByTarget(10L, 1000))
                .thenReturn(List.of(100L));
        Instant publishTime = Instant.parse("2026-06-23T10:00:00Z");

        service.reconcilePost(999L, post("published", "public", publishTime));

        verify(setOps).remove(FeedTimelineService.BIGV_AUTHORS_KEY, "10");
        verify(zSetOps).add("feed:timeline:100", "999", (double) publishTime.toEpochMilli());
    }

    @Test
    void reconcilePrivatePostRemovesItFromEveryFollowerTimeline() {
        when(relationMapper.listActiveFollowerIdsByTargetAfter(10L, 0L, 500))
                .thenReturn(List.of(100L, 101L));

        service.reconcilePost(999L, post("published", "private", Instant.now()));

        verify(zSetOps).remove("feed:timeline:100", "999");
        verify(zSetOps).remove("feed:timeline:101", "999");
        verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void privatePostRemovalUsesStableKeysetWhenFollowersChangeBetweenPages() {
        List<Long> firstPage = LongStream.rangeClosed(1L, 500L).boxed().toList();
        when(relationMapper.listActiveFollowerIdsByTargetAfter(10L, 0L, 500))
                .thenReturn(firstPage);
        when(relationMapper.listActiveFollowerIdsByTargetAfter(10L, 500L, 500))
                .thenReturn(List.of(502L));

        service.reconcilePost(999L, post("published", "private", Instant.now()));

        verify(zSetOps).remove("feed:timeline:500", "999");
        verify(zSetOps).remove("feed:timeline:502", "999");
        verify(relationMapper, never()).listFollowers(anyLong(), anyInt(), anyInt());
    }

    @Test
    void given_unfollow_when_removeAuthorFromTimeline_then_zremPostIds() {
        when(postMapper.listPublishedIdsByCreatorSince(eq(20L), any(Instant.class), eq(10_000)))
                .thenReturn(List.of(1L, 2L));

        service.removeAuthorFromTimeline(100L, 20L);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(zSetOps).remove(eq("feed:timeline:100"), captor.capture());
        assertThat(captor.getValue()).containsExactly("1", "2");
    }

    @Test
    void given_followedBigV_when_getFollowedBigVAuthors_then_returnsIntersection() {
        when(setOps.members(FeedTimelineService.BIGV_AUTHORS_KEY)).thenReturn(Set.of("20", "30", "40"));
        when(relationService.following(100L, 500, 0)).thenReturn(List.of(10L, 20L, 30L));

        List<Long> result = service.getFollowedBigVAuthors(100L);

        assertThat(result).containsExactly(20L, 30L);
    }

    @Test
    void timelineCandidateBatchTracksRawRankProgressPastMalformedMembers() {
        when(zSetOps.reverseRange("feed:timeline:100", 50L, 99L))
                .thenReturn(new java.util.LinkedHashSet<>(List.of("10", "bad", "9")));

        FeedTimelineService.TimelineCandidateBatch batch =
                service.getTimelinePostIdBatch(100L, 50L, 50);

        assertThat(batch.postIds()).containsExactly(10L, 9L);
        assertThat(batch.scannedCount()).isEqualTo(3);
        assertThat(batch.exhausted()).isTrue();
    }

    @Test
    void rejectedTimelineCandidatesAreRemovedInOneBoundedCall() {
        service.removeTimelinePostIds(100L, List.of(10L, 11L));

        verify(zSetOps).remove("feed:timeline:100", "10", "11");
    }

    private static Post post(String status, String visible, Instant publishTime) {
        return Post.builder()
                .id(999L)
                .creatorId(10L)
                .status(status)
                .visible(visible)
                .publishTime(publishTime)
                .build();
    }

    private static PostFeedRow feedRow(long id, Instant publishTime) {
        PostFeedRow row = new PostFeedRow();
        row.setId(id);
        row.setPublishTime(publishTime);
        return row;
    }
}
