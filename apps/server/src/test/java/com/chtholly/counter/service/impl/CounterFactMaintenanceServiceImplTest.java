package com.chtholly.counter.service.impl;

import com.chtholly.counter.mapper.CounterPersistenceMapper;
import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.mapper.CounterReactionMapper;
import com.chtholly.counter.service.CounterFactMaintenanceService;
import com.chtholly.counter.service.CounterFactMaintenanceService.ManagedPostReactionState;
import com.chtholly.counter.service.CounterFactMaintenanceService.PostReactionReconciliationResult;
import com.chtholly.counter.service.CounterFactMaintenanceService.ReactionReconciliationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterFactMaintenanceServiceImplTest {

    @Mock
    private CounterReactionMapper reactionMapper;
    @Mock
    private CounterPersistenceMapper persistenceMapper;
    @Mock
    private CounterCalibrationService calibrationService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    private CounterFactMaintenanceService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        lenient().when(persistenceMapper.lockReactionEpochs(anyString(), anyString()))
                .thenReturn(List.of(0L, 0L));
        service = new CounterFactMaintenanceServiceImpl(
                reactionMapper,
                persistenceMapper,
                calibrationService,
                transactionManager);
    }

    @Test
    void rejectsInvalidInputBeforeAnyExternalInteraction() {
        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(), Set.of(10L), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(10L),
                Map.of(11L, new ManagedPostReactionState(Set.of(), Set.of()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(10L),
                Map.of(10L, new ManagedPostReactionState(Set.of(2L), Set.of()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                new HashSet<>(Arrays.asList(1L, null)), Set.of(10L), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(-10L), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        Map<Long, ManagedPostReactionState> nullState = new HashMap<>();
        nullState.put(10L, null);
        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(10L), nullState))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(
                reactionMapper, persistenceMapper, calibrationService, transactionManager);
    }

    @Test
    void reconcilesOnlyManagedMysqlFactsThenRebuildsTheWholeProjection() {
        CounterReactionKey likeOne = key(10L, "like", 1L);
        CounterReactionKey likeTwo = key(10L, "like", 2L);
        CounterReactionKey favOne = key(10L, "fav", 1L);
        CounterReactionKey favTwo = key(10L, "fav", 2L);
        when(reactionMapper.findExisting(List.of(likeOne, likeTwo, favOne, favTwo)))
                .thenReturn(List.of(likeOne, favTwo));
        when(reactionMapper.insertAllIgnore(List.of(likeTwo, favOne))).thenReturn(2);
        when(reactionMapper.deleteAll(List.of(likeOne, favTwo))).thenReturn(2);
        when(calibrationService.reconcileEntity("post", "10"))
                .thenReturn(new CounterCalibrationService.ReconciliationResult(3L, 2L, 5L));

        ReactionReconciliationResult result = service.reconcileManagedPostReactions(
                Set.of(1L, 2L),
                Set.of(10L),
                Map.of(10L, new ManagedPostReactionState(Set.of(2L), Set.of(1L))));

        assertThat(result.posts()).containsEntry(
                10L,
                new PostReactionReconciliationResult(10L, 2L, 2L, 3L, 2L));
        verify(persistenceMapper).ensureReactionSnapshots("post", "10");
        verify(persistenceMapper).lockReactionEpochs("post", "10");
        InOrder order = inOrder(transactionManager, calibrationService);
        order.verify(transactionManager).commit(transactionStatus);
        order.verify(calibrationService).reconcileEntity("post", "10");
    }

    @Test
    void idempotentRerunSkipsDmlButStillRepairsTheProjection() {
        CounterReactionKey like = key(10L, "like", 1L);
        CounterReactionKey favorite = key(10L, "fav", 2L);
        when(reactionMapper.findExisting(List.of(
                like, key(10L, "like", 2L), key(10L, "fav", 1L), favorite)))
                .thenReturn(List.of(like, favorite));
        when(calibrationService.reconcileEntity("post", "10"))
                .thenReturn(new CounterCalibrationService.ReconciliationResult(1L, 1L, 2L));

        ReactionReconciliationResult result = service.reconcileManagedPostReactions(
                Set.of(1L, 2L),
                Set.of(10L),
                Map.of(10L, new ManagedPostReactionState(Set.of(1L), Set.of(2L))));

        assertThat(result.posts().get(10L).managedInsertCount()).isZero();
        assertThat(result.posts().get(10L).managedDeleteCount()).isZero();
        verify(reactionMapper, never()).insertAllIgnore(anyList());
        verify(reactionMapper, never()).deleteAll(anyList());
        verify(calibrationService).reconcileEntity("post", "10");
    }

    @Test
    void managedQueriesAndWritesStayWithinFiveHundredKeyBatches() {
        Set<Long> users = LongStream.rangeClosed(1L, 501L)
                .boxed()
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        when(reactionMapper.findExisting(anyList())).thenReturn(List.of());
        when(reactionMapper.insertAllIgnore(anyList()))
                .thenAnswer(invocation -> invocation.<List<?>>getArgument(0).size());
        when(calibrationService.reconcileEntity("post", "10"))
                .thenReturn(new CounterCalibrationService.ReconciliationResult(501L, 0L, 1L));

        service.reconcileManagedPostReactions(
                users,
                Set.of(10L),
                Map.of(10L, new ManagedPostReactionState(users, Set.of())));

        ArgumentCaptor<List<CounterReactionKey>> queryBatches = ArgumentCaptor.forClass(List.class);
        verify(reactionMapper, times(3)).findExisting(queryBatches.capture());
        assertThat(queryBatches.getAllValues())
                .allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(500));
        assertThat(queryBatches.getAllValues().stream().mapToInt(List::size).sum())
                .isEqualTo(1_002);
        ArgumentCaptor<List<CounterReactionKey>> insertBatches = ArgumentCaptor.forClass(List.class);
        verify(reactionMapper, times(2)).insertAllIgnore(insertBatches.capture());
        assertThat(insertBatches.getAllValues())
                .allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(500));
    }

    @Test
    void mysqlFailureRollsBackAndDoesNotStartProjectionRecovery() {
        when(reactionMapper.findExisting(anyList()))
                .thenThrow(new IllegalStateException("mysql unavailable"));

        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(10L), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql unavailable");

        verify(transactionManager).rollback(transactionStatus);
        verify(calibrationService, never()).reconcileEntity(anyString(), anyString());
    }

    @Test
    void projectionFailureHappensAfterTheManagedFactsCommitAndCanBeRetried() {
        when(reactionMapper.findExisting(anyList())).thenReturn(List.of());
        when(reactionMapper.insertAllIgnore(List.of(key(10L, "like", 1L)))).thenReturn(1);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(calibrationService).reconcileEntity("post", "10");

        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L),
                Set.of(10L),
                Map.of(10L, new ManagedPostReactionState(Set.of(1L), Set.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis unavailable");

        InOrder order = inOrder(transactionManager, calibrationService);
        order.verify(transactionManager).commit(transactionStatus);
        order.verify(calibrationService).reconcileEntity("post", "10");
    }

    @Test
    void laterPostFailureDoesNotRollBackAnEarlierCompletedPost() {
        when(reactionMapper.findExisting(anyList())).thenReturn(List.of());
        when(calibrationService.reconcileEntity("post", "10"))
                .thenReturn(new CounterCalibrationService.ReconciliationResult(0L, 0L, 1L));
        when(calibrationService.reconcileEntity("post", "20"))
                .thenThrow(new IllegalStateException("second failed"));

        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(20L, 10L), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("second failed");

        verify(calibrationService).reconcileEntity("post", "10");
        verify(calibrationService).reconcileEntity("post", "20");
        verify(transactionManager, times(2)).commit(transactionStatus);
    }

    @Test
    void inconsistentSnapshotEpochFailsBeforeReadingManagedFacts() {
        when(persistenceMapper.lockReactionEpochs("post", "10"))
                .thenReturn(List.of(1L, 2L));

        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(10L), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("epoch");

        verify(reactionMapper, never()).findExisting(anyList());
        verify(transactionManager).rollback(transactionStatus);
    }

    private static CounterReactionKey key(long postId, String metric, long userId) {
        return new CounterReactionKey("post", Long.toString(postId), metric, userId);
    }
}
