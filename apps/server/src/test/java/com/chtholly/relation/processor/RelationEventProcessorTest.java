package com.chtholly.relation.processor;

import com.chtholly.counter.service.UserCounterService;
import com.chtholly.relation.event.RelationEvent;
import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.service.impl.RelationCacheInvalidator;
import com.chtholly.post.feed.FeedTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

@ExtendWith(MockitoExtension.class)
class RelationEventProcessorTest {

    @Mock
    private RelationMapper relationMapper;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private UserCounterService userCounterService;

    @Mock
    private RelationCacheInvalidator cacheInvalidator;

    @Mock
    private FeedTimelineService feedTimelineService;

    private RelationEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RelationEventProcessor(
                relationMapper, redis, userCounterService, cacheInvalidator, feedTimelineService);
    }

    @Test
    void staleCreatedEventAfterAuthorityWasCanceledConvergesToCanceledProjection() {
        when(relationMapper.findActiveFollowingRow(11L, 22L)).thenReturn(null);
        when(redis.hasKey("uf:flws:11")).thenReturn(true);
        when(redis.hasKey("uf:fans:22")).thenReturn(true);
        when(redis.opsForZSet()).thenReturn(zSetOperations);

        processor.process(new RelationEvent("FollowCreated", 11L, 22L, 101L));

        verify(relationMapper).cancelFollower(22L, 11L);
        verify(relationMapper, never()).insertFollower(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
        verify(zSetOperations).remove("uf:flws:11", "22");
        verify(zSetOperations).remove("uf:fans:22", "11");
        verify(redis).expire("uf:flws:11", Duration.ofHours(2));
        verify(redis).expire("uf:fans:22", Duration.ofHours(2));
        verifyCountersRebuiltOnce();
        verifyNoCounterDeltas();
        verify(cacheInvalidator).invalidateLocalProjection(11L, 22L);
        verify(feedTimelineService).removeAuthorFromTimeline(11L, 22L);
    }

    @Test
    void staleCanceledEventAfterAuthorityWasRecreatedConvergesToActiveProjection() {
        LocalDateTime jdbcCreatedAt = LocalDateTime.of(2026, 7, 19, 1, 2, 3);
        Timestamp createdAt = Timestamp.valueOf(jdbcCreatedAt);
        when(relationMapper.findActiveFollowingRow(11L, 22L))
                .thenReturn(Map.of("id", 202L, "createdAt", jdbcCreatedAt));
        when(redis.hasKey("uf:flws:11")).thenReturn(true);
        when(redis.hasKey("uf:fans:22")).thenReturn(true);
        when(redis.opsForZSet()).thenReturn(zSetOperations);

        processor.process(new RelationEvent("FollowCanceled", 11L, 22L, null));

        verify(relationMapper).insertFollower(202L, 22L, 11L, 1, createdAt);
        verify(relationMapper, never()).cancelFollower(22L, 11L);
        verify(zSetOperations).add("uf:flws:11", "22", createdAt.getTime());
        verify(zSetOperations).add("uf:fans:22", "11", createdAt.getTime());
        verify(redis).expire("uf:flws:11", Duration.ofHours(2));
        verify(redis).expire("uf:fans:22", Duration.ofHours(2));
        verifyCountersRebuiltOnce();
        verifyNoCounterDeltas();
        verify(cacheInvalidator).invalidateLocalProjection(11L, 22L);
        verify(feedTimelineService, never()).removeAuthorFromTimeline(11L, 22L);
    }

    @Test
    void reprocessingSameEventUsesIdempotentProjectionWithoutCounterDeltas() {
        when(relationMapper.findActiveFollowingRow(11L, 22L)).thenReturn(Map.of(
                "id", 202L,
                "createdAt", Timestamp.from(Instant.parse("2026-07-19T01:02:03Z"))));
        RelationEvent event = new RelationEvent("FollowCreated", 11L, 22L, 202L);

        processor.process(event);
        processor.process(event);

        verify(relationMapper, times(2)).insertFollower(
                org.mockito.ArgumentMatchers.eq(202L),
                org.mockito.ArgumentMatchers.eq(22L),
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.any());
        verify(userCounterService, times(2)).rebuildAllCounters(11L);
        verify(userCounterService, times(2)).rebuildAllCounters(22L);
        verifyNoCounterDeltas();
    }

    @Test
    void activeProjectionDoesNotCreatePartialCachesWhenKeysAreAbsent() {
        when(relationMapper.findActiveFollowingRow(11L, 22L)).thenReturn(Map.of(
                "id", 202L,
                "createdAt", Timestamp.from(Instant.parse("2026-07-19T01:02:03Z"))));

        processor.process(new RelationEvent("FollowCreated", 11L, 22L, 202L));

        verify(zSetOperations, never()).add(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyDouble());
        verify(redis, never()).expire(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class));
        verify(cacheInvalidator).invalidateLocalProjection(11L, 22L);
    }

    private void verifyCountersRebuiltOnce() {
        verify(userCounterService).rebuildAllCounters(11L);
        verify(userCounterService).rebuildAllCounters(22L);
    }

    private void verifyNoCounterDeltas() {
        verify(userCounterService, never()).incrementFollowings(anyLong(), anyInt());
        verify(userCounterService, never()).incrementFollowers(anyLong(), anyInt());
    }
}
