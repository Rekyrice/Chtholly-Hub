package com.chtholly.counter.service.impl;

import com.chtholly.counter.event.CounterEventPublisher;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
class CounterServiceImplBatchTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private CounterEventPublisher counterEventPublisher;
    @Mock
    private RedissonClient redisson;
    @Mock
    private PostMapper postMapper;
    @Mock
    private UserMapper userMapper;

    private CounterService counterService;

    @BeforeEach
    void setUp() {
        counterService = new CounterServiceImpl(redis, counterEventPublisher, redisson, postMapper, userMapper);
    }

    @Test
    void batchIsLikedUsesSinglePipeline() {
        when(redis.executePipelined(any(RedisCallback.class))).thenReturn(List.of(true, false, true));

        Map<Long, Boolean> result = counterService.batchIsLiked(42L, List.of(1L, 2L, 3L));

        verify(redis).executePipelined(any(RedisCallback.class));
        assertThat(result).containsEntry(1L, true).containsEntry(2L, false).containsEntry(3L, true);
    }

    @Test
    void batchIsFavedUsesSinglePipeline() {
        when(redis.executePipelined(any(RedisCallback.class))).thenReturn(List.of(false, true));

        Map<Long, Boolean> result = counterService.batchIsFaved(7L, List.of(10L, 20L));

        verify(redis).executePipelined(any(RedisCallback.class));
        assertThat(result).containsEntry(10L, false).containsEntry(20L, true);
    }

    @Test
    void batchReturnsEmptyForEmptyInput() {
        assertThat(counterService.batchIsLiked(1L, List.of())).isEmpty();
        assertThat(counterService.batchIsFaved(1L, null)).isEmpty();
    }

    @Test
    void effectiveCountReadsAggregatedAndPendingStateAtomically() {
        doReturn(37L).when(redis).execute(
                any(DefaultRedisScript.class), anyList(), eq("0"), eq("4"), eq("5"));

        assertThat(counterService.getEffectiveCount("post", "99", "view")).isEqualTo(37L);

        ArgumentCaptor<DefaultRedisScript<Long>> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(script.capture(), keys.capture(), eq("0"), eq("4"), eq("5"));
        assertThat(keys.getValue()).containsExactly("cnt:v1:post:99", "agg:v1:post:99");
        assertThat(script.getValue().getScriptAsString()).contains("redis.call('GET'", "redis.call('HGET'");
    }

    @Test
    void missingSdsWithPendingViewReturnsDeltaWithoutDeletingAggregationField() {
        doReturn(null).when(redis).execute(any(RedisCallback.class));
        doReturn(12L).when(redis).execute(
                any(DefaultRedisScript.class), anyList(), eq("0"), eq("4"), eq("5"));

        assertThat(counterService.getCounts("post", "new-post", List.of("view")))
                .containsEntry("view", 12L);

        verify(redis, never()).opsForHash();
    }
}
