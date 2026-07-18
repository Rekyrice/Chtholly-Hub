package com.chtholly.counter.event;

import com.chtholly.counter.mapper.CounterPersistenceMapper;
import com.chtholly.counter.mapper.CounterSnapshotDelta;
import com.chtholly.counter.schema.CounterKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterAggregationProcessorTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private HashOperations<String, Object, Object> hashOps;
    @Mock
    private CounterPersistenceMapper persistenceMapper;

    private CounterAggregationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CounterAggregationProcessor(redis, persistenceMapper);
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        lenient().when(redis.opsForHash()).thenReturn(hashOps);
    }

    @Test
    void duplicateEventInsideBatchProducesOnePersistentDeltaWithoutRedisReactionAggregation() {
        CounterEvent event = CounterEvent.of("evt-1", "post", "7", "like", 1, 100L, 1);
        when(persistenceMapper.insertInbox(event)).thenReturn(1, 0);
        when(persistenceMapper.countMatchingInbox(event)).thenReturn(1);

        assertThat(processor.applyBatch(List.of(event, event))).isEqualTo(1);

        ArgumentCaptor<List<CounterSnapshotDelta>> deltas = ArgumentCaptor.forClass(List.class);
        verify(persistenceMapper).incrementSnapshots(deltas.capture());
        assertThat(deltas.getValue()).containsExactly(new CounterSnapshotDelta("post", "7", "like", 1L, 0L));
        verify(redis, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void differentEventIdsForSameCounterAreGroupedIntoOneSnapshotDelta() {
        CounterEvent first = CounterEvent.of("evt-1", "post", "7", "fav", 2, 100L, 1);
        CounterEvent second = CounterEvent.of("evt-2", "post", "7", "fav", 2, 101L, 1);
        when(persistenceMapper.insertInbox(first)).thenReturn(1);
        when(persistenceMapper.insertInbox(second)).thenReturn(1);

        assertThat(processor.applyBatch(List.of(first, second))).isEqualTo(2);

        verify(persistenceMapper).incrementSnapshots(List.of(
                new CounterSnapshotDelta("post", "7", "fav", 2L, 0L)));
    }

    @Test
    void eventsFromDifferentFactEpochsAreNotMergedIntoOneSnapshotDelta() {
        CounterEvent stale = CounterEvent.of("evt-old", "post", "7", "like", 1, 100L, 1);
        stale.setFactEpoch(2L);
        CounterEvent current = CounterEvent.of("evt-new", "post", "7", "like", 1, 101L, 1);
        current.setFactEpoch(3L);
        when(persistenceMapper.insertInbox(stale)).thenReturn(1);
        when(persistenceMapper.insertInbox(current)).thenReturn(1);

        processor.applyBatch(List.of(stale, current));

        ArgumentCaptor<List<CounterSnapshotDelta>> deltas = ArgumentCaptor.forClass(List.class);
        verify(persistenceMapper).incrementSnapshots(deltas.capture());
        assertThat(deltas.getValue()).containsExactly(
                new CounterSnapshotDelta("post", "7", "like", 1L, 2L),
                new CounterSnapshotDelta("post", "7", "like", 1L, 3L));
    }

    @Test
    void invalidMetricIndexIsRejectedBeforeInboxInsert() {
        CounterEvent invalid = CounterEvent.of("evt-bad", "post", "7", "like", 2, 100L, 1);

        assertThatThrownBy(() -> processor.applyBatch(List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metric and index");

        verify(persistenceMapper, never()).insertInbox(any());
    }

    @Test
    void oversizedEntityIdentityIsRejectedInsteadOfBeingTruncatedByMysqlIgnore() {
        CounterEvent invalid = CounterEvent.of(
                "evt-bad", "x".repeat(33), "7".repeat(65), "like", 1, 100L, 1);

        assertThatThrownBy(() -> processor.applyBatch(List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity");

        verify(persistenceMapper, never()).insertInbox(any());
    }

    @Test
    void nonAsciiEventIdIsRejectedBeforeAsciiInboxInsert() {
        CounterEvent invalid = CounterEvent.of("事件-1", "post", "7", "like", 1, 100L, 1);

        assertThatThrownBy(() -> processor.applyBatch(List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("US-ASCII");

        verify(persistenceMapper, never()).insertInbox(any());
    }

    @Test
    void duplicateEventIdWithDifferentPayloadIsRejectedAsCollision() {
        CounterEvent event = CounterEvent.of("evt-1", "post", "7", "like", 1, 100L, 1);
        when(persistenceMapper.insertInbox(event)).thenReturn(0);
        when(persistenceMapper.countMatchingInbox(event)).thenReturn(0);

        assertThatThrownBy(() -> processor.applyBatch(List.of(event)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collision");

        verify(persistenceMapper, never()).incrementSnapshots(anyList());
    }

    @Test
    void snapshotFailurePropagatesSoTransactionCanRollBackInbox() {
        CounterEvent event = CounterEvent.of("evt-1", "post", "7", "like", 1, 100L, 1);
        when(persistenceMapper.insertInbox(event)).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("mysql down"))
                .when(persistenceMapper).incrementSnapshots(anyList());

        assertThatThrownBy(() -> processor.applyBatch(List.of(event)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mysql down");
    }

    @Test
    void newViewEventKeepsExistingRedisAggregationPath() {
        CounterEvent event = CounterEvent.of("evt-view", "post", "7", "view", 0, 0L, 3);
        when(persistenceMapper.insertInbox(event)).thenReturn(1);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(1L);

        assertThat(processor.applyBatch(List.of(event))).isEqualTo(1);

        verify(redis).execute(any(DefaultRedisScript.class), eq(List.of(
                        CounterKeys.aggKey("post", "7"), CounterKeys.aggIndexKey(),
                        "counter:event:evt-view", CounterKeys.factEpochKey("post", "7"))),
                eq("0"), eq("3"), eq("604800"), eq("0"), eq("0"));
    }

    @Test
    void flushStillTransfersOnlyIndexedViewAggregation() {
        String aggKey = CounterKeys.aggKey("post", "42");
        when(setOps.members(CounterKeys.aggIndexKey())).thenReturn(Set.of(aggKey));
        when(hashOps.entries(aggKey)).thenReturn(Map.of("0", "2"));
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any())).thenReturn(2L);

        processor.flush();

        verify(redis).execute(any(DefaultRedisScript.class), eq(List.of(
                        CounterKeys.sdsKey("post", "42"), aggKey, CounterKeys.aggIndexKey())),
                eq("5"), eq("4"), eq("0"));
    }
}
