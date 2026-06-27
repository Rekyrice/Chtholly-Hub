package com.chtholly.counter.service.impl;

import com.chtholly.counter.event.CounterEventProducer;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
class CounterServiceImplBatchTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private CounterEventProducer eventProducer;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private RedissonClient redisson;
    @Mock
    private PostMapper postMapper;
    @Mock
    private UserMapper userMapper;

    private CounterService counterService;

    @BeforeEach
    void setUp() {
        counterService = new CounterServiceImpl(redis, eventProducer, eventPublisher, redisson, postMapper, userMapper);
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
}
