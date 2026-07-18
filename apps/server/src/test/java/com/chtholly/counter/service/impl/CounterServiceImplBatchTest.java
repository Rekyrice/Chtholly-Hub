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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
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
    private PostMapper postMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private CounterCalibrationService calibrationService;

    private CounterService counterService;

    @BeforeEach
    void setUp() {
        counterService = new CounterServiceImpl(
                redis, counterEventPublisher, postMapper, userMapper, calibrationService);
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
        doThrow(new IllegalStateException("Counter reconciliation lock is busy"))
                .when(calibrationService).reconcileEntity("post", "99");

        assertThatThrownBy(() -> counterService.like("post", "99", 42L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getHttpStatus()).isEqualTo(503));

        verify(counterEventPublisher, never()).publish(any());
        verify(calibrationService).reconcileEntity("post", "99");
    }

    @Test
    void staleMaintenanceFenceIsTakenOverBeforeTheToggleRetries() {
        doReturn(List.of(-1L, 0L), List.of(1L, 1L)).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));

        assertThat(counterService.like("post", "99", 42L)).isTrue();

        verify(calibrationService).reconcileEntity("post", "99");
        verify(redis, times(2)).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
        verify(counterEventPublisher).publish(any());
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
                "counter:fact-epoch:post:99",
                "bmidx:like:post:99",
                "bmidx:fav:post:99",
                "counter:calibration:reaction-bitmap:candidates",
                "bmidxcnt:like:post:99",
                "bmidxcnt:fav:post:99");
        assertThat(script.getValue().getScriptAsString()).contains(
                "string.len(raw)", "SET', cntKey", "SADD', bitmapIndexKey",
                "SADD', peerBitmapIndexKey", "SREM', bitmapIndexKey");
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
    void missingReactionSdsUsesAuthoritativeCalibrationBeforeReturningCounts() {
        byte[] calibratedSds = ByteBuffer.allocate(20)
                .putInt(19)
                .putInt(7)
                .putInt(3)
                .putInt(0)
                .putInt(0)
                .array();
        doReturn(null, calibratedSds, calibratedSds)
                .when(redis).execute(any(RedisCallback.class));

        assertThat(counterService.getCounts("post", "99", List.of("like")))
                .containsEntry("like", 7L);
        assertThat(counterService.getCounts("post", "99", List.of("fav")))
                .containsEntry("fav", 3L);

        verify(calibrationService).reconcileEntity("post", "99");
    }

    @Test
    void missingReactionSdsFailsClosedWhenAuthoritativeCalibrationCannotRestoreIt() {
        doReturn(null, null).when(redis).execute(any(RedisCallback.class));

        assertThatThrownBy(() -> counterService.getCounts("post", "99", List.of("like")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getHttpStatus()).isEqualTo(503));

        verify(calibrationService).reconcileEntity("post", "99");
    }

    @Test
    void missingBatchEntryCalibratesFromAuthoritativeBitmapBeforeReturningCounts() {
        byte[] calibratedSds = ByteBuffer.allocate(20)
                .putInt(19)
                .putInt(7)
                .putInt(3)
                .putInt(0)
                .putInt(0)
                .array();
        when(redis.executePipelined(any(RedisCallback.class)))
                .thenReturn(Collections.singletonList(null));
        doReturn(null, calibratedSds).when(redis).execute(any(RedisCallback.class));

        Map<String, Map<String, Long>> result =
                counterService.getCountsBatch("post", List.of("99"), List.of("like", "fav"));

        assertThat(result).containsKey("99");
        assertThat(result.get("99"))
                .containsEntry("like", 7L)
                .containsEntry("fav", 3L);

        verify(calibrationService).reconcileEntity("post", "99");
    }
}
