package com.chtholly.relation.service;

import com.chtholly.counter.service.UserCounterService;
import com.chtholly.relation.mapper.RelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelationCounterQueryServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private UserCounterService userCounterService;
    @Mock
    private RelationMapper relationMapper;

    private RelationCounterQueryService service;

    @BeforeEach
    void setUp() {
        service = new RelationCounterQueryService(redis, userCounterService, relationMapper);
    }

    @Test
    void mysqlReactionFactsOverrideStaleUserCounterSegments() {
        when(redis.execute(any(RedisCallback.class)))
                .thenReturn(encoded(1L, 2L, 3L, 99L, 88L));
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        when(userCounterService.countLikesReceived(10L)).thenReturn(7L);
        when(userCounterService.countFavsReceived(10L)).thenReturn(4L);

        Map<String, Long> counters = service.getCounters(10L);

        assertThat(counters).containsExactly(
                Map.entry("followings", 1L),
                Map.entry("followers", 2L),
                Map.entry("posts", 3L),
                Map.entry("likedPosts", 7L),
                Map.entry("favedPosts", 4L));
        verify(userCounterService, never()).rebuildAllCounters(10L);
    }

    @Test
    void missingProjection_rebuildsThenFallsBackToAvailableAuthoritativeFacts() {
        when(redis.execute(any(RedisCallback.class))).thenReturn(null, null);
        when(userCounterService.countLikesReceived(12L)).thenReturn(5L);
        when(userCounterService.countFavsReceived(12L)).thenReturn(6L);

        Map<String, Long> counters = service.getCounters(12L);

        assertThat(counters).containsExactly(
                Map.entry("followings", 0L),
                Map.entry("followers", 0L),
                Map.entry("posts", 0L),
                Map.entry("likedPosts", 5L),
                Map.entry("favedPosts", 6L));
        verify(userCounterService).rebuildAllCounters(12L);
    }

    @Test
    void sampledMismatch_rebuildsAndReturnsFreshProjection() {
        when(redis.execute(any(RedisCallback.class))).thenReturn(
                encoded(1L, 2L, 3L, 4L, 5L),
                encoded(8L, 9L, 10L, 11L, 12L));
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(relationMapper.countFollowingActive(20L)).thenReturn(8);
        when(relationMapper.countFollowerActive(20L)).thenReturn(9);
        when(userCounterService.countLikesReceived(20L)).thenReturn(13L);
        when(userCounterService.countFavsReceived(20L)).thenReturn(14L);

        Map<String, Long> counters = service.getCounters(20L);

        assertThat(counters).containsExactly(
                Map.entry("followings", 8L),
                Map.entry("followers", 9L),
                Map.entry("posts", 10L),
                Map.entry("likedPosts", 13L),
                Map.entry("favedPosts", 14L));
        verify(userCounterService).rebuildAllCounters(20L);
    }

    private static byte[] encoded(long... values) {
        byte[] raw = new byte[values.length * 4];
        for (int index = 0; index < values.length; index++) {
            long value = values[index];
            int offset = index * 4;
            raw[offset] = (byte) (value >>> 24);
            raw[offset + 1] = (byte) (value >>> 16);
            raw[offset + 2] = (byte) (value >>> 8);
            raw[offset + 3] = (byte) value;
        }
        return raw;
    }
}
