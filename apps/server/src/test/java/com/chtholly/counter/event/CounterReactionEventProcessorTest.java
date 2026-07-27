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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock
    private CounterReactionSideEffectReceiptService sideEffectReceiptService;

    private CounterReactionEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CounterReactionEventProcessor(
                reactionMapper,
                projectionStore,
                aggregationProcessor,
                eventPublisher,
                idempotencyGuard,
                sideEffectReceiptService);
        org.mockito.Mockito.lenient()
                .when(sideEffectReceiptService.publishIfPending(
                        anyString(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return true;
                });
    }

    @Test
    void durableApplyLocksTheEntityBeforeReadingAndProjectingMysqlTerminalState() {
        CounterEvent oldLike = event("11", "7", "like", 42L, 1);
        CounterEvent unlike = event("12", "7", "like", 42L, -1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of());
        when(aggregationProcessor.applyBatchWithResult(List.of(oldLike, unlike)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(
                        2, List.of(oldLike, unlike)));

        processor.process(List.of(oldLike, unlike));

        InOrder order = inOrder(aggregationProcessor, reactionMapper, projectionStore);
        order.verify(aggregationProcessor).applyBatchWithResult(List.of(oldLike, unlike));
        order.verify(reactionMapper).findExisting(List.of(key));
        order.verify(projectionStore).project(Map.of(key, false));
        InOrder sideEffects = inOrder(eventPublisher, idempotencyGuard);
        sideEffects.verify(eventPublisher).publishEvent(oldLike);
        verify(sideEffectReceiptService)
                .publishIfPending(eq("11"), any(Runnable.class));
        sideEffects.verify(idempotencyGuard)
                .markConsumed("counter-reaction-side-effects", 11L);
        sideEffects.verify(eventPublisher).publishEvent(unlike);
        verify(sideEffectReceiptService)
                .publishIfPending(eq("12"), any(Runnable.class));
        sideEffects.verify(idempotencyGuard)
                .markConsumed("counter-reaction-side-effects", 12L);
    }

    @Test
    void processingOwnsTheTransactionWhileAggregationJoinsIt() throws Exception {
        Method process = CounterReactionEventProcessor.class.getMethod("process", List.class);
        Transactional processing = process.getAnnotation(Transactional.class);
        Method apply = CounterAggregationProcessor.class.getMethod(
                "applyBatchWithResult", List.class);
        Transactional aggregation = apply.getAnnotation(Transactional.class);

        assertThat(processing).isNotNull();
        assertThat(processing.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(aggregation).isNotNull();
        assertThat(aggregation.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    @Test
    void sideEffectsArePublishedOnlyAfterTheCoreTransactionCommits() {
        CounterEvent event = event("28", "7", "like", 42L, 1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        when(aggregationProcessor.applyBatchWithResult(List.of(event)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(
                        1, List.of(event)));
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of(key));

        TransactionSynchronizationManager.initSynchronization();
        try {
            processor.process(List.of(event));

            verify(eventPublisher, never()).publishEvent(event);
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(eventPublisher).publishEvent(event);
        verify(sideEffectReceiptService)
                .publishIfPending(eq("28"), any(Runnable.class));
        verify(idempotencyGuard)
                .markConsumed("counter-reaction-side-effects", 28L);
    }

    @Test
    void currentMysqlPresenceWinsOverOldUnlikeProjectionWhileItsSideEffectStillReplays() {
        CounterEvent oldUnlike = event("13", "7", "like", 42L, -1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of(key));
        when(aggregationProcessor.applyBatchWithResult(List.of(oldUnlike)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(0, List.of(oldUnlike)));

        processor.process(List.of(oldUnlike));

        verify(projectionStore).project(Map.of(key, true));
        verify(eventPublisher).publishEvent(oldUnlike);
        verify(sideEffectReceiptService)
                .publishIfPending(eq("13"), any(Runnable.class));
        verify(idempotencyGuard)
                .markConsumed("counter-reaction-side-effects", 13L);
    }

    @Test
    void currentMysqlAbsenceWinsOverOldLikeProjectionWhileItsSideEffectStillReplays() {
        CounterEvent oldLike = event("18", "7", "like", 42L, 1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of());
        when(aggregationProcessor.applyBatchWithResult(List.of(oldLike)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(0, List.of(oldLike)));

        processor.process(List.of(oldLike));

        verify(projectionStore).project(Map.of(key, false));
        verify(eventPublisher).publishEvent(oldLike);
        verify(sideEffectReceiptService)
                .publishIfPending(eq("18"), any(Runnable.class));
        verify(idempotencyGuard)
                .markConsumed("counter-reaction-side-effects", 18L);
    }

    @Test
    void publishesEveryCommittedTransitionInOutboxOrder() {
        CounterEvent firstLike = event("22", "7", "like", 42L, 1);
        CounterEvent unlike = event("23", "7", "like", 42L, -1);
        CounterEvent finalLike = event("24", "7", "like", 42L, 1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        List<CounterEvent> events = List.of(firstLike, unlike, finalLike);
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of(key));
        when(aggregationProcessor.applyBatchWithResult(events))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(3, events));

        processor.process(events);

        InOrder sideEffects = inOrder(eventPublisher, idempotencyGuard);
        sideEffects.verify(eventPublisher).publishEvent(firstLike);
        sideEffects.verify(idempotencyGuard)
                .markConsumed("counter-reaction-side-effects", 22L);
        sideEffects.verify(eventPublisher).publishEvent(unlike);
        sideEffects.verify(idempotencyGuard)
                .markConsumed("counter-reaction-side-effects", 23L);
        sideEffects.verify(eventPublisher).publishEvent(finalLike);
        sideEffects.verify(idempotencyGuard)
                .markConsumed("counter-reaction-side-effects", 24L);
    }

    @Test
    void preservesEveryCommittedTransitionWhenRapidChangesArriveInSeparateBatches() {
        CounterEvent firstLike = event("25", "7", "like", 42L, 1);
        CounterEvent unlike = event("26", "7", "like", 42L, -1);
        CounterEvent finalLike = event("27", "7", "like", 42L, 1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of(key));
        when(aggregationProcessor.applyBatchWithResult(List.of(firstLike)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(
                        1, List.of(firstLike)));
        when(aggregationProcessor.applyBatchWithResult(List.of(unlike)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(
                        1, List.of(unlike)));
        when(aggregationProcessor.applyBatchWithResult(List.of(finalLike)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(
                        1, List.of(finalLike)));

        processor.process(List.of(firstLike));
        processor.process(List.of(unlike));
        processor.process(List.of(finalLike));

        InOrder sideEffects = inOrder(eventPublisher, idempotencyGuard);
        sideEffects.verify(eventPublisher).publishEvent(firstLike);
        sideEffects.verify(idempotencyGuard)
                .markConsumed("counter-reaction-side-effects", 25L);
        sideEffects.verify(eventPublisher).publishEvent(unlike);
        sideEffects.verify(idempotencyGuard)
                .markConsumed("counter-reaction-side-effects", 26L);
        sideEffects.verify(eventPublisher).publishEvent(finalLike);
        sideEffects.verify(idempotencyGuard)
                .markConsumed("counter-reaction-side-effects", 27L);
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
        List<CounterReactionKey> expectedKeys = events.stream()
                .map(event -> new CounterReactionKey(
                        event.getEntityType(),
                        event.getEntityId(),
                        event.getMetric(),
                        event.getUserId()))
                .toList();
        when(reactionMapper.findExisting(anyList())).thenAnswer(invocation -> {
            List<CounterReactionKey> chunk = invocation.getArgument(0);
            return chunk.size() == 1
                    ? List.of(chunk.getFirst())
                    : List.of(chunk.getFirst(), chunk.getLast());
        });
        when(aggregationProcessor.applyBatchWithResult(events))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(501, List.of()));

        processor.process(events);

        ArgumentCaptor<List<CounterReactionKey>> chunks = ArgumentCaptor.forClass(List.class);
        verify(reactionMapper, times(2)).findExisting(chunks.capture());
        assertThat(chunks.getAllValues()).containsExactly(
                expectedKeys.subList(0, 500),
                expectedKeys.subList(500, 501));
        verify(projectionStore).project(org.mockito.ArgumentMatchers.argThat(
                targets -> targets.size() == 501
                        && targets.get(expectedKeys.get(0))
                        && !targets.get(expectedKeys.get(1))
                        && targets.get(expectedKeys.get(499))
                        && targets.get(expectedKeys.get(500))));
    }

    @Test
    void redisProjectionFailureDoesNotPublishSideEffects() {
        CounterEvent event = event("14", "7", "fav", 42L, 1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "fav", 42L);
        when(aggregationProcessor.applyBatchWithResult(List.of(event)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(
                        1, List.of(event)));
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of(key));
        doThrow(new IllegalStateException("redis down"))
                .when(projectionStore).project(Map.of(key, true));

        assertThatThrownBy(() -> processor.process(List.of(event)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis down");

        verify(aggregationProcessor).applyBatchWithResult(List.of(event));
        verifyNoInteractions(eventPublisher, idempotencyGuard);
        verifyNoInteractions(sideEffectReceiptService);
    }

    @Test
    void receiptFailureCannotMarkTheBestEffortGuardBeforeMysqlCommit() {
        CounterEvent event = event("29", "7", "like", 42L, 1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        when(aggregationProcessor.applyBatchWithResult(List.of(event)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(
                        1, List.of(event)));
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of(key));
        when(sideEffectReceiptService.publishIfPending(
                eq("29"), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    throw new IllegalStateException("mysql down");
                });

        assertThatThrownBy(() -> processor.process(List.of(event)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql down");

        verify(eventPublisher).publishEvent(event);
        verify(idempotencyGuard, never())
                .markConsumed("counter-reaction-side-effects", 29L);
    }

    @Test
    void staleRedisGuardCannotSuppressAPendingMysqlReceipt() {
        CounterEvent event = event("30", "7", "like", 42L, 1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        when(aggregationProcessor.applyBatchWithResult(List.of(event)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(
                        1, List.of(event)));
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of(key));
        org.mockito.Mockito.lenient().when(idempotencyGuard.isAlreadyConsumed(
                "counter-reaction-side-effects", 30L))
                .thenReturn(true);

        processor.process(List.of(event));

        verify(eventPublisher).publishEvent(event);
        verify(idempotencyGuard)
                .markConsumed("counter-reaction-side-effects", 30L);
        verify(idempotencyGuard, never())
                .isAlreadyConsumed("counter-reaction-side-effects", 30L);
        verify(sideEffectReceiptService)
                .publishIfPending(eq("30"), any(Runnable.class));
    }

    @Test
    void redisGuardWriteFailureDoesNotRollBackTheMysqlReceipt() {
        CounterEvent event = event("31", "7", "like", 42L, 1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        when(aggregationProcessor.applyBatchWithResult(List.of(event)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(
                        1, List.of(event)));
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of(key));
        doThrow(new IllegalStateException("redis down"))
                .when(idempotencyGuard)
                .markConsumed("counter-reaction-side-effects", 31L);

        processor.process(List.of(event));

        verify(eventPublisher).publishEvent(event);
        verify(sideEffectReceiptService)
                .publishIfPending(eq("31"), any(Runnable.class));
    }

    @Test
    void oneListenerFailureDoesNotBlockLaterEventsButStillRequestsBrokerRetry() {
        CounterEvent failing = event("32", "7", "like", 42L, 1);
        CounterEvent succeeding = event("33", "8", "fav", 43L, 1);
        List<CounterEvent> events = List.of(failing, succeeding);
        List<CounterReactionKey> keys = List.of(
                new CounterReactionKey("post", "7", "like", 42L),
                new CounterReactionKey("post", "8", "fav", 43L));
        when(aggregationProcessor.applyBatchWithResult(events))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(2, events));
        when(reactionMapper.findExisting(keys)).thenReturn(keys);
        doThrow(new IllegalStateException("listener down"))
                .when(eventPublisher).publishEvent(failing);

        assertThatThrownBy(() -> processor.process(events))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("listener down");

        verify(eventPublisher).publishEvent(succeeding);
        verify(sideEffectReceiptService)
                .publishIfPending(eq("33"), any(Runnable.class));
        verify(idempotencyGuard)
                .markConsumed("counter-reaction-side-effects", 33L);
    }

    @Test
    void repeatedSharedFailureInstanceStillDoesNotBlockLaterEvents() {
        CounterEvent first = event("34", "7", "like", 42L, 1);
        CounterEvent second = event("35", "8", "fav", 43L, 1);
        CounterEvent succeeding = event("36", "9", "like", 44L, 1);
        List<CounterEvent> events = List.of(first, second, succeeding);
        List<CounterReactionKey> keys = List.of(
                new CounterReactionKey("post", "7", "like", 42L),
                new CounterReactionKey("post", "8", "fav", 43L),
                new CounterReactionKey("post", "9", "like", 44L));
        when(aggregationProcessor.applyBatchWithResult(events))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(3, events));
        when(reactionMapper.findExisting(keys)).thenReturn(keys);
        IllegalStateException sharedFailure =
                new IllegalStateException("shared listener failure");
        doThrow(sharedFailure).when(eventPublisher).publishEvent(first);
        doThrow(sharedFailure).when(eventPublisher).publishEvent(second);

        assertThatThrownBy(() -> processor.process(events))
                .isSameAs(sharedFailure);

        verify(eventPublisher).publishEvent(succeeding);
        verify(sideEffectReceiptService)
                .publishIfPending(eq("36"), any(Runnable.class));
    }

    @Test
    void mysqlAggregationFailurePreventsRedisProjectionAndSideEffects() {
        CounterEvent event = event("15", "7", "like", 42L, 1);
        when(aggregationProcessor.applyBatchWithResult(List.of(event)))
                .thenThrow(new IllegalStateException("mysql down"));

        assertThatThrownBy(() -> processor.process(List.of(event)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql down");

        verifyNoInteractions(reactionMapper, projectionStore);
        verifyNoInteractions(eventPublisher, idempotencyGuard);
        verifyNoInteractions(sideEffectReceiptService);
    }

    @Test
    void ordinaryBrokerReplaySkipsAlreadyPublishedLocalSideEffects() {
        CounterEvent event = event("16", "7", "like", 42L, 1);
        CounterReactionKey key = new CounterReactionKey("post", "7", "like", 42L);
        when(reactionMapper.findExisting(List.of(key))).thenReturn(List.of(key));
        when(aggregationProcessor.applyBatchWithResult(List.of(event)))
                .thenReturn(new CounterAggregationProcessor.ApplyBatchResult(0, List.of(event)));
        when(sideEffectReceiptService.publishIfPending(
                eq("16"), any(Runnable.class))).thenReturn(false);

        processor.process(List.of(event));

        verify(eventPublisher, never()).publishEvent(event);
        verify(sideEffectReceiptService)
                .publishIfPending(eq("16"), any(Runnable.class));
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
        verifyNoInteractions(
                projectionStore,
                aggregationProcessor,
                eventPublisher,
                idempotencyGuard,
                sideEffectReceiptService);
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
