package com.chtholly.counter.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.mapper.CounterReactionMapper;
import com.chtholly.counter.service.CounterReactionCommandService;
import com.chtholly.counter.service.CounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
class CounterServiceImplBatchTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private CounterCalibrationService calibrationService;
    @Mock
    private CounterReactionCommandService reactionCommandService;
    @Mock
    private CounterReactionMapper reactionMapper;
    @Mock
    private CounterReactionProjectionStore reactionProjectionStore;

    private CounterService counterService;

    @BeforeEach
    void setUp() {
        counterService = new CounterServiceImpl(
                redis,
                reactionCommandService,
                reactionMapper,
                reactionProjectionStore,
                calibrationService);
    }

    @Test
    void singleMembershipUsesCompleteProjectionWithoutMysql() {
        CounterReactionKey like = new CounterReactionKey("post", "1", "like", 42L);
        CounterReactionKey favorite = new CounterReactionKey("post", "1", "fav", 42L);
        when(reactionProjectionStore.read(like)).thenReturn(Optional.of(true));
        when(reactionProjectionStore.read(favorite)).thenReturn(Optional.of(false));

        assertThat(counterService.isLiked("post", "1", 42L)).isTrue();
        assertThat(counterService.isFaved("post", "1", 42L)).isFalse();

        verify(reactionMapper, never()).exists(any(), any(), any(), any(Long.class));
    }

    @Test
    void singleMembershipFallsBackToMysqlWhenProjectionIsUnknown() {
        CounterReactionKey key = new CounterReactionKey("post", "1", "like", 42L);
        when(reactionProjectionStore.read(key)).thenReturn(Optional.empty());
        when(reactionMapper.exists("post", "1", "like", 42L)).thenReturn(1);

        assertThat(counterService.isLiked("post", "1", 42L)).isTrue();

        verify(reactionMapper).exists("post", "1", "like", 42L);
    }

    @Test
    void batchMembershipCombinesProjectionHitsWithOneMysqlFallbackQuery() {
        CounterReactionKey first = new CounterReactionKey("post", "1", "like", 42L);
        CounterReactionKey second = new CounterReactionKey("post", "2", "like", 42L);
        CounterReactionKey third = new CounterReactionKey("post", "3", "like", 42L);
        Map<CounterReactionKey, Optional<Boolean>> projection = new LinkedHashMap<>();
        projection.put(first, Optional.of(true));
        projection.put(second, Optional.empty());
        projection.put(third, Optional.of(false));
        when(reactionProjectionStore.readBatch(List.of(first, second, third)))
                .thenReturn(projection);
        when(reactionMapper.findExistingEntityIds(
                "post", "like", 42L, List.of("2")))
                .thenReturn(List.of("2"));

        Map<Long, Boolean> result =
                counterService.batchIsLiked(42L, List.of(1L, 2L, 3L, 2L));

        assertThat(result)
                .containsExactly(
                        Map.entry(1L, true),
                        Map.entry(2L, true),
                        Map.entry(3L, false));
        verify(reactionProjectionStore).readBatch(List.of(first, second, third));
        verify(reactionMapper).findExistingEntityIds(
                "post", "like", 42L, List.of("2"));
    }

    @Test
    void batchMembershipTreatsMissingMysqlFactAsFalse() {
        CounterReactionKey first = new CounterReactionKey("post", "10", "fav", 7L);
        CounterReactionKey second = new CounterReactionKey("post", "20", "fav", 7L);
        when(reactionProjectionStore.readBatch(List.of(first, second)))
                .thenReturn(Map.of(first, Optional.empty(), second, Optional.of(true)));
        when(reactionMapper.findExistingEntityIds(
                "post", "fav", 7L, List.of("10")))
                .thenReturn(List.of());

        Map<Long, Boolean> result = counterService.batchIsFaved(7L, List.of(10L, 20L));

        assertThat(result).containsExactly(Map.entry(10L, false), Map.entry(20L, true));
    }

    @Test
    void batchMembershipRejectsAnIncompleteMysqlResult() {
        CounterReactionKey key = new CounterReactionKey("post", "10", "like", 7L);
        when(reactionProjectionStore.readBatch(List.of(key)))
                .thenReturn(Map.of(key, Optional.empty()));
        when(reactionMapper.findExistingEntityIds(
                "post", "like", 7L, List.of("10")))
                .thenReturn(null);

        assertThatThrownBy(() -> counterService.batchIsLiked(7L, List.of(10L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MySQL");
    }

    @Test
    void batchReturnsEmptyForEmptyInput() {
        assertThat(counterService.batchIsLiked(1L, List.of())).isEmpty();
        assertThat(counterService.batchIsFaved(1L, null)).isEmpty();
        assertThat(counterService.batchIsLiked(1L, java.util.Arrays.asList(null, null))).isEmpty();

        verify(reactionProjectionStore, never()).readBatch(anyList());
        verify(reactionMapper, never()).findExistingEntityIds(any(), any(), any(Long.class), anyList());
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
    void reactionWritesDelegateToTheMysqlCommandService() {
        when(reactionCommandService.setReaction("post", "99", "like", 42L, true))
                .thenReturn(true);
        when(reactionCommandService.setReaction("post", "99", "like", 42L, false))
                .thenReturn(false);
        when(reactionCommandService.setReaction("post", "99", "fav", 42L, true))
                .thenReturn(true);
        when(reactionCommandService.setReaction("post", "99", "fav", 42L, false))
                .thenReturn(false);

        assertThat(counterService.like("post", "99", 42L)).isTrue();
        assertThat(counterService.unlike("post", "99", 42L)).isFalse();
        assertThat(counterService.fav("post", "99", 42L)).isTrue();
        assertThat(counterService.unfav("post", "99", 42L)).isFalse();

        verify(redis, never()).execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class));
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
    void missingBatchEntryCalibratesFromMysqlFactsBeforeReturningCounts() {
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
