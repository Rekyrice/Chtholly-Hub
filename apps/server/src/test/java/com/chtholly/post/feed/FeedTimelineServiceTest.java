package com.chtholly.post.feed;

import com.chtholly.post.mapper.PostMapper;
import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.service.RelationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
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
        when(relationMapper.countFollowerActive(10L)).thenReturn(2);
        when(relationService.followers(10L, 500, 0)).thenReturn(List.of(100L, 101L));

        Instant publishTime = Instant.parse("2026-06-23T10:00:00Z");
        service.onPostPublished(10L, 999L, publishTime);

        verify(zSetOps, times(2)).add(anyString(), eq("999"), eq((double) publishTime.toEpochMilli()));
        verify(setOps, never()).add(eq(FeedTimelineService.BIGV_AUTHORS_KEY), anyString());
    }

    @Test
    void given_bigVAuthor_when_onPostPublished_then_marksBigVWithoutPush() {
        when(relationMapper.countFollowerActive(10L)).thenReturn(1000);

        service.onPostPublished(10L, 999L, Instant.now());

        verify(setOps).add(FeedTimelineService.BIGV_AUTHORS_KEY, "10");
        verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
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
}
