package com.chtholly.counter.service.impl;

import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.schema.CounterKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterReactionProjectionStoreTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CounterReactionProjectionStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOperations);
        store = new CounterReactionProjectionStore(redis);
    }

    @Test
    void projectsDeduplicatedTargetsInOnePipelineAndRestoresPriorCompleteEntity() {
        CounterReactionKey like = new CounterReactionKey("post", "7", "like", 42L);
        CounterReactionKey favorite = new CounterReactionKey("post", "7", "fav", 42L);
        String marker = CounterKeys.reactionProjectionCompleteKey("post", "7");
        when(valueOperations.multiGet(List.of(marker)))
                .thenReturn(List.of(CounterReactionProjectionStore.COMPLETE_VERSION));
        when(redis.executePipelined(any(SessionCallback.class)))
                .thenReturn(List.of(List.of(1L, 1L), List.of(0L, 0L)))
                .thenReturn(List.of(1L));
        Map<CounterReactionKey, Boolean> targets = new LinkedHashMap<>();
        targets.put(like, true);
        targets.put(favorite, false);

        store.project(targets);

        verify(redis).delete(List.of(marker));
        verify(redis, times(2)).executePipelined(any(SessionCallback.class));
    }

    @Test
    void missingSdsKeepsProjectionIncompleteWithoutFailingTheDurableEvent() {
        CounterReactionKey like = new CounterReactionKey("post", "7", "like", 42L);
        String marker = CounterKeys.reactionProjectionCompleteKey("post", "7");
        when(valueOperations.multiGet(List.of(marker)))
                .thenReturn(List.of(CounterReactionProjectionStore.COMPLETE_VERSION));
        when(redis.executePipelined(any(SessionCallback.class)))
                .thenReturn(List.of(List.of(2L, 1L)));

        store.project(Map.of(like, true));

        verify(redis).delete(List.of(marker));
        verify(redis, times(1)).executePipelined(any(SessionCallback.class));
    }

    @Test
    void activeMaintenanceFenceFailsAndLeavesMarkerMissingForRetry() {
        CounterReactionKey like = new CounterReactionKey("post", "7", "like", 42L);
        String marker = CounterKeys.reactionProjectionCompleteKey("post", "7");
        when(valueOperations.multiGet(List.of(marker)))
                .thenReturn(List.of(CounterReactionProjectionStore.COMPLETE_VERSION));
        when(redis.executePipelined(any(SessionCallback.class)))
                .thenReturn(List.of(List.of(-1L, 0L)));

        assertThatThrownBy(() -> store.project(Map.of(like, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maintenance");

        verify(redis).delete(List.of(marker));
        verify(redis).executePipelined(any(SessionCallback.class));
    }

    @Test
    void batchReadReturnsUnknownForIncompleteProjectionWithoutExtraRedisRoundTrips() {
        CounterReactionKey first = new CounterReactionKey("post", "7", "like", 42L);
        CounterReactionKey second = new CounterReactionKey("post", "8", "like", 42L);
        CounterReactionKey third = new CounterReactionKey("post", "9", "like", 42L);
        when(redis.executePipelined(any(SessionCallback.class)))
                .thenReturn(List.of(1L, -1L, 0L));

        Map<CounterReactionKey, Optional<Boolean>> result =
                store.readBatch(List.of(first, second, third));

        assertThat(result.get(first)).contains(true);
        assertThat(result.get(second)).isEmpty();
        assertThat(result.get(third)).contains(false);
        verify(redis).executePipelined(any(SessionCallback.class));
        verify(redis, never()).execute(any(), any(), any(Object[].class));
    }

    @Test
    void emptyProjectionBatchDoesNotTouchRedis() {
        assertThat(store.readBatch(List.of())).isEmpty();
        store.project(Map.of());

        verify(redis, never()).executePipelined(any(SessionCallback.class));
        verify(valueOperations, never()).multiGet(any());
    }
}
