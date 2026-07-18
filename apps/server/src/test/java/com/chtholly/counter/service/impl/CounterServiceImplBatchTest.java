package com.chtholly.counter.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.counter.event.CounterEvent;
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
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RRateLimiter;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;

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
    @Mock
    private CounterCalibrationService calibrationService;

    private CounterService counterService;

    @BeforeEach
    void setUp() {
        counterService = new CounterServiceImpl(
                redis, counterEventPublisher, redisson, postMapper, userMapper, calibrationService);
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

    @Test
    void reactionWriteRejectedByMaintenanceFenceReturnsServiceUnavailableWithoutPublishing() {
        doReturn(List.of(-1L, 0L)).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));

        assertThatThrownBy(() -> counterService.like("post", "99", 42L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getHttpStatus()).isEqualTo(503));

        verify(counterEventPublisher, never()).publish(any());
    }

    @Test
    void oversizedEntityIdentityFailsBeforeRedisMutationOrEventPublication() {
        assertThatThrownBy(() -> counterService.like("x".repeat(33), "7".repeat(65), 42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity");
        assertThatThrownBy(() -> counterService.like("post", "bad:*", 42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity");

        verify(redis, never()).execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class));
        verify(counterEventPublisher, never()).publish(any());
    }

    @Test
    void changedReactionCarriesCurrentFactEpochAndChecksFenceBeforeBitmapMutation() {
        doReturn(List.of(1L, 7L)).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));

        assertThat(counterService.like("post", "99", 42L)).isTrue();

        ArgumentCaptor<CounterEvent> event = ArgumentCaptor.forClass(CounterEvent.class);
        verify(counterEventPublisher).publish(event.capture());
        assertThat(event.getValue().getFactEpoch()).isEqualTo(7L);

        ArgumentCaptor<DefaultRedisScript<List>> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(script.capture(), keys.capture(), any(Object[].class));
        assertThat(keys.getValue()).containsExactly(
                "bm:like:post:99:0",
                "cnt:v1:post:99",
                "counter:fact-maintenance:post:99",
                "counter:fact-epoch:post:99");
        assertThat(script.getValue().getScriptAsString()).contains("string.len(raw)", "SET', cntKey");
        assertThat(script.getValue().getScriptAsString().indexOf("EXISTS', fenceKey"))
                .isLessThan(script.getValue().getScriptAsString().indexOf("SETBIT', bmKey"));
    }

    @Test
    void missingSdsCalibratesAndRetriesOnlyOnceBeforePublishing() {
        doReturn(List.of(2L, 4L), List.of(1L, 5L)).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));

        assertThat(counterService.like("post", "99", 42L)).isTrue();

        verify(calibrationService).reconcileEntity("post", "99");
        verify(redis, times(2)).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
        ArgumentCaptor<CounterEvent> event = ArgumentCaptor.forClass(CounterEvent.class);
        verify(counterEventPublisher).publish(event.capture());
        assertThat(event.getValue().getFactEpoch()).isEqualTo(5L);
    }

    @Test
    void repeatedMissingSdsFailsClosedWithoutPublishing() {
        doReturn(List.of(2L, 4L), List.of(2L, 5L)).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));

        assertThatThrownBy(() -> counterService.like("post", "99", 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reconciliation");

        verify(calibrationService).reconcileEntity("post", "99");
        verify(counterEventPublisher, never()).publish(any());
    }

    @Test
    void missingPostSdsRebuildUsesTheSameEntityLockAsFactMaintenance() throws Exception {
        RBucket<Long> bucket = mock(RBucket.class);
        RRateLimiter limiter = mock(RRateLimiter.class);
        RLock lock = mock(RLock.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        Cursor<String> cursor = mock(Cursor.class);
        when(redisson.getBucket(anyString())).thenReturn((RBucket) bucket);
        when(bucket.get()).thenReturn(null);
        when(redisson.getRateLimiter("rl:sds-rebuild:post:99")).thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(true);
        when(redisson.getLock("lock:counter-fact-maintenance:post:99")).thenReturn(lock);
        when(lock.tryLock(0L, java.util.concurrent.TimeUnit.MILLISECONDS)).thenReturn(true);
        doReturn(null).when(redis).execute(any(RedisCallback.class));
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);
        when(redis.opsForHash()).thenReturn(hash);

        assertThat(counterService.getCounts("post", "99", List.of("like")))
                .containsEntry("like", 0L);

        verify(redisson).getLock("lock:counter-fact-maintenance:post:99");
        verify(lock).unlock();
    }

    @Test
    void missingPostSdsRebuildRereadsStructureAfterLockAndPreservesMaintenanceResult() throws Exception {
        RBucket<Long> bucket = mock(RBucket.class);
        RRateLimiter limiter = mock(RRateLimiter.class);
        RLock lock = mock(RLock.class);
        byte[] maintainedSds = ByteBuffer.allocate(20)
                .putInt(19)
                .putInt(7)
                .putInt(3)
                .putInt(0)
                .putInt(0)
                .array();
        when(redisson.getBucket(anyString())).thenReturn((RBucket) bucket);
        when(bucket.get()).thenReturn(null);
        when(redisson.getRateLimiter("rl:sds-rebuild:post:99")).thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(true);
        when(redisson.getLock("lock:counter-fact-maintenance:post:99")).thenReturn(lock);
        when(lock.tryLock(0L, java.util.concurrent.TimeUnit.MILLISECONDS)).thenReturn(true);
        doReturn(null, maintainedSds).when(redis).execute(any(RedisCallback.class));

        assertThat(counterService.getCounts("post", "99", List.of("like", "fav")))
                .containsEntry("like", 7L)
                .containsEntry("fav", 3L);

        verify(redis, times(2)).execute(any(RedisCallback.class));
        verify(redis, never()).scan(any(ScanOptions.class));
        verify(redis, never()).opsForHash();
        verify(lock).unlock();
    }
}
