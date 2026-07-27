package com.chtholly.relation.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.counter.service.UserCounterService;
import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.service.RelationService;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelationControllerCounterTest {

    @Mock
    private RelationService relationService;
    @Mock
    private JwtService jwtService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private UserCounterService userCounterService;
    @Mock
    private RelationMapper relationMapper;

    private RelationController controller;

    @BeforeEach
    void setUp() {
        controller = new RelationController(
                relationService,
                jwtService,
                redis,
                userCounterService,
                relationMapper);
    }

    @Test
    void mysqlReactionFactsOverrideStaleUserCounterSegments() {
        when(redis.execute(any(RedisCallback.class)))
                .thenReturn(encoded(1L, 2L, 3L, 99L, 88L));
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any()))
                .thenReturn(false);
        when(userCounterService.countLikesReceived(10L)).thenReturn(7L);
        when(userCounterService.countFavsReceived(10L)).thenReturn(4L);

        Map<String, Long> counters = controller.counter(10L);

        assertThat(counters).containsExactly(
                Map.entry("followings", 1L),
                Map.entry("followers", 2L),
                Map.entry("posts", 3L),
                Map.entry("likedPosts", 7L),
                Map.entry("favedPosts", 4L));
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
