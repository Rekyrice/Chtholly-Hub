package com.chtholly.counter.event;

import com.chtholly.common.kafka.idempotency.OutboxIdempotencyGuard;
import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.mapper.CounterReactionMapper;
import com.chtholly.counter.service.impl.CounterReactionProjectionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterReactionEventProcessorTest {

    @Mock
    private CounterReactionMapper reactionMapper;
    @Mock
    private CounterReactionProjectionStore projectionStore;
    @Mock
    private CounterAggregationProcessor aggregationProcessor;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private OutboxIdempotencyGuard idempotencyGuard;

    private CounterReactionEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CounterReactionEventProcessor(
                reactionMapper,
                projectionStore,
                aggregationProcessor,
                eventPublisher,
                idempotencyGuard);
    }

    @Test
    void deduplicatesRelationLookupAndProjectsMysqlTerminalStateBeforeAggregation() {
        CounterEvent oldLike = event("11", "7", "like", 42L, 1);
        CounterEvent unlike = event("12", "7", "like", 42L, -1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of());
        when(aggregationProcessor.applyBatchWithResult(List.of(oldLike, unlike)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(
                        2, List.of(oldLike, unlike)));

        processor.process(List.of(oldLike, unlike));

        InOrder order = inOrder(reactionMapper, projectionStore, aggregationProcessor);
        order.verify(reactionMapper).findExisting(List.of(key));
        order.verify(projectionStore).project(Map.of(key, false));
        order.verify(aggregationProcessor).applyBatchWithResult(List.of(oldLike, unlike));
        verify(eventPublisher).publishEvent(oldLike);
        verify(eventPublisher).publishEvent(unlike);
        verify(idempotencyGuard).markConsumed("counter-reaction-side-effects", 11L);
        verify(idempotencyGuard).markConsumed("counter-reaction-side-effects", 12L);
    }

    @Test
    void currentMysqlPresenceWinsOverReplayedOldUnlikeEvent() {
        CounterEvent oldUnlike = event("13", "7", "like", 42L, -1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of(key));
        when(aggregationProcessor.applyBatchWithResult(List.of(oldUnlike)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(0, List.of(oldUnlike)));

        processor.process(List.of(oldUnlike));

        verify(projectionStore).project(Map.of(key, true));
    }

    @Test
    void currentMysqlAbsenceWinsOverReplayedOldLikeEvent() {
        CounterEvent oldLike = event("18", "7", "like", 42L, 1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of());
        when(aggregationProcessor.applyBatchWithResult(List.of(oldLike)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(0, List.of()));

        processor.process(List.of(oldLike));

        verify(projectionStore).project(Map.of(key, false));
        verifyNoInteractions(eventPublisher, idempotencyGuard);
    }

    @Test
    void processesMultipleEntitiesUsersAndMetricsInOneMysqlBatch() {
        List<CounterEvent> events = List.of(
                event("19", "7", "like", 42L, 1),
                event("20", "7", "fav", 42L, 1),
                event("21", "8", "like", 43L, -1));
        List<CounterReactionKey> keys = List.of(
                new CounterReactionKey("post", "7", "like", 42L),
                new CounterReactionKey("post", "7", "fav", 42L),
                new CounterReactionKey("post", "8", "like", 43L));
        when(reactionMapper.findExisting(keys)).thenReturn(List.of(keys.get(0), keys.get(2)));
        when(aggregationProcessor.applyBatchWithResult(events))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(3, List.of()));

        processor.process(events);

        Map<CounterReactionKey, Boolean> expected = new LinkedHashMap<>();
        expected.put(keys.get(0), true);
        expected.put(keys.get(1), false);
        expected.put(keys.get(2), true);
        verify(reactionMapper).findExisting(keys);
        verify(projectionStore).project(expected);
        verify(aggregationProcessor).applyBatchWithResult(events);
    }

    @Test
    void chunksFiveHundredAndOneDistinctKeysIntoBoundedMysqlQueries() {
        List<CounterEvent> events = IntStream.rangeClosed(1, 501)
                .mapToObj(index -> event(
                        Integer.toString(1_000 + index),
                        Integer.toString(index),
                        index % 2 == 0 ? "like" : "fav",
                        10_000L + index,
                        1))
                .toList();
        when(reactionMapper.findExisting(anyList())).thenReturn(List.of());
        when(aggregationProcessor.applyBatchWithResult(events))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(501, List.of()));

        processor.process(events);

        ArgumentCaptor<List<CounterReactionKey>> chunks = ArgumentCaptor.forClass(List.class);
        verify(reactionMapper, times(2)).findExisting(chunks.capture());
        assertThat(chunks.getAllValues()).extracting(List::size).containsExactly(500, 1);
        assertThat(chunks.getAllValues().getFirst().getFirst().entityId()).isEqualTo("1");
        assertThat(chunks.getAllValues().getLast().getFirst().entityId()).isEqualTo("501");
        verify(projectionStore).project(org.mockito.ArgumentMatchers.argThat(
                targets -> targets.size() == 501 && targets.values().stream().noneMatch(Boolean::booleanValue)));
    }

    @Test
    void redisProjectionFailurePreventsInboxCommitAndSideEffects() {
        CounterEvent event = event("14", "7", "fav", 42L, 1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "fav", 42L);
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of(key));
        doThrow(new IllegalStateException("redis down"))
                .when(projectionStore).project(Map.of(key, true));

        assertThatThrownBy(() -> processor.process(List.of(event)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis down");

        verifyNoInteractions(aggregationProcessor, eventPublisher, idempotencyGuard);
    }

    @Test
    void mysqlAggregationFailureAfterRedisProjectionDoesNotPublishSideEffects() {
        CounterEvent event = event("15", "7", "like", 42L, 1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of(key));
        when(aggregationProcessor.applyBatchWithResult(List.of(event)))
                .thenThrow(new IllegalStateException("mysql down"));

        assertThatThrownBy(() -> processor.process(List.of(event)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql down");

        verify(projectionStore).project(Map.of(key, true));
        verifyNoInteractions(eventPublisher, idempotencyGuard);
    }

    @Test
    void ordinaryBrokerReplaySkipsAlreadyPublishedLocalSideEffects() {
        CounterEvent event = event("16", "7", "like", 42L, 1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of(key));
        when(aggregationProcessor.applyBatchWithResult(List.of(event)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(0, List.of(event)));
        when(idempotencyGuard.isAlreadyConsumed("counter-reaction-side-effects", 16L))
                .thenReturn(true);

        processor.process(List.of(event));

        verify(eventPublisher, never()).publishEvent(event);
        verify(idempotencyGuard, never()).markConsumed("counter-reaction-side-effects", 16L);
    }

    @Test
    void invalidReactionIsRejectedBeforeAnyExternalCall() {
        CounterEvent invalid = event("17", "7", "like", 42L, 1);
        invalid.setIdx(2);

        assertThatThrownBy(() -> processor.process(List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metric");

        verify(reactionMapper, never()).findExisting(anyList());
        verifyNoInteractions(projectionStore, aggregationProcessor, eventPublisher, idempotencyGuard);
    }

    private static CounterEvent event(
            String eventId,
            String entityId,
            String metric,
            long userId,
            int delta) {
        int index = "like".equals(metric) ? 1 : 2;
        return CounterEvent.of(eventId, "post", entityId, metric, index, userId, delta);
    }
}
