package com.chtholly.counter.service.impl;

import com.chtholly.counter.mapper.CounterEntityIdentity;
import com.chtholly.counter.mapper.CounterPersistenceMapper;
import com.chtholly.counter.schema.CounterKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterCalibrationServiceTest {

    @Mock
    private RedissonClient redisson;
    @Mock
    private RLock lock;
    @Mock
    private CounterPersistenceMapper persistenceMapper;
    @Mock
    private CounterReactionProjectionRebuilder projectionRebuilder;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    private CounterCalibrationService service;

    @BeforeEach
    void setUp() {
        service = new CounterCalibrationService(
                redisson,
                persistenceMapper,
                projectionRebuilder,
                transactionManager,
                true,
                2);
    }

    @Test
    void entityReconciliationRebuildsFromMysqlAtTheNextLockedEpoch() throws Exception {
        prepareLockAndTransaction("post", "7");
        when(persistenceMapper.lockReactionEpochs("post", "7")).thenReturn(List.of(4L, 4L));
        when(projectionRebuilder.rebuild(eq("post"), eq("7"), anyString(), eq(5L)))
                .thenReturn(new CounterReactionProjectionRebuilder.RebuildResult(2L, 3L, 5L));

        CounterCalibrationService.ReconciliationResult result =
                service.reconcileEntity("post", "7");

        assertThat(result).isEqualTo(new CounterCalibrationService.ReconciliationResult(2L, 3L, 5L));
        InOrder order = inOrder(projectionRebuilder, persistenceMapper, transactionManager);
        order.verify(projectionRebuilder).begin(eq("post"), eq("7"), anyString());
        order.verify(persistenceMapper).ensureReactionSnapshots("post", "7");
        order.verify(persistenceMapper).lockReactionEpochs("post", "7");
        order.verify(projectionRebuilder).rebuild(eq("post"), eq("7"), anyString(), eq(5L));
        order.verify(persistenceMapper).replaceReactionSnapshots("post", "7", 2L, 3L, 5L);
        order.verify(transactionManager).commit(transactionStatus);
        order.verify(projectionRebuilder)
                .publishComplete(eq("post"), eq("7"), anyString());
        verify(projectionRebuilder, never()).abort(anyString(), anyString(), anyString());
        verify(lock).unlock();
    }

    @Test
    void reconciliationUsesAnIndependentTransactionDefinition() throws Exception {
        prepareLockAndTransaction("post", "7");
        when(persistenceMapper.lockReactionEpochs("post", "7")).thenReturn(List.of(4L, 4L));
        when(projectionRebuilder.rebuild(eq("post"), eq("7"), anyString(), eq(5L)))
                .thenReturn(new CounterReactionProjectionRebuilder.RebuildResult(2L, 3L, 5L));

        service.reconcileEntity("post", "7");

        ArgumentCaptor<TransactionDefinition> definition =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void callerManagedTransactionIsRejectedBeforeTakingTheMaintenanceLock() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.reconcileEntity("post", "7"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active transaction");
        } finally {
            TransactionSynchronizationManager.clear();
        }

        verify(redisson, never()).getLock(anyString());
        verifyNoInteractions(persistenceMapper, projectionRebuilder, transactionManager);
    }

    @Test
    void rebuildFailureRollsBackAndKeepsTheProjectionIncomplete() throws Exception {
        prepareLockAndTransaction("post", "7");
        when(persistenceMapper.lockReactionEpochs("post", "7")).thenReturn(List.of(4L, 4L));
        doThrow(new IllegalStateException("stage failed"))
                .when(projectionRebuilder)
                .rebuild(eq("post"), eq("7"), anyString(), eq(5L));

        assertThatThrownBy(() -> service.reconcileEntity("post", "7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stage failed");

        verify(transactionManager).rollback(transactionStatus);
        verify(projectionRebuilder).abort(eq("post"), eq("7"), anyString());
        verify(persistenceMapper, never())
                .replaceReactionSnapshots(
                        anyString(), anyString(), anyLong(), anyLong(), anyLong());
        verify(lock).unlock();
    }

    @Test
    void transactionCommitFailureInvalidatesTheCompletedProjection() throws Exception {
        prepareLockAndTransaction("post", "7");
        when(persistenceMapper.lockReactionEpochs("post", "7")).thenReturn(List.of(0L, 0L));
        when(projectionRebuilder.rebuild(eq("post"), eq("7"), anyString(), eq(1L)))
                .thenReturn(new CounterReactionProjectionRebuilder.RebuildResult(1L, 0L, 1L));
        doThrow(new IllegalStateException("commit failed"))
                .when(transactionManager).commit(transactionStatus);

        assertThatThrownBy(() -> service.reconcileEntity("post", "7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("commit failed");

        verify(projectionRebuilder).abort(eq("post"), eq("7"), anyString());
        verify(projectionRebuilder, never())
                .publishComplete(anyString(), anyString(), anyString());
        verify(lock).unlock();
    }

    @Test
    void busyEntityLockDoesNotTouchTheProjectionOrMysql() throws Exception {
        when(redisson.getLock(CounterKeys.factMaintenanceLockKey("post", "7"))).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.MILLISECONDS)).thenReturn(false);

        assertThatThrownBy(() -> service.reconcileEntity("post", "7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("busy");

        verify(projectionRebuilder, never()).begin(anyString(), anyString(), anyString());
        verify(transactionManager, never()).getTransaction(any());
        verify(lock, never()).unlock();
    }

    @Test
    void scheduledRunUsesOnlyOneBoundedMysqlSnapshotPage() {
        CounterEntityIdentity first = new CounterEntityIdentity("post", "first");
        CounterEntityIdentity second = new CounterEntityIdentity("post", "second");
        when(persistenceMapper.findReactionSnapshotIdentityHighWatermark())
                .thenReturn(second);
        when(persistenceMapper.listReactionSnapshotIdentitiesPage(
                null, null, "post", "second", 2))
                .thenReturn(List.of(first, second));
        CounterCalibrationService spy = spy(service);
        doReturn(new CounterCalibrationService.ReconciliationResult(1L, 0L, 1L))
                .when(spy).reconcileEntity("post", "first");
        doReturn(new CounterCalibrationService.ReconciliationResult(0L, 1L, 1L))
                .when(spy).reconcileEntity("post", "second");

        spy.reconcileScheduled();

        verify(spy).reconcileEntity("post", "first");
        verify(spy).reconcileEntity("post", "second");
        verify(persistenceMapper).listReactionSnapshotIdentitiesPage(
                null, null, "post", "second", 2);
    }

    @Test
    void failedCandidatesCannotStarveLaterPagesInTheStableMysqlSweep() {
        CounterEntityIdentity first = new CounterEntityIdentity("post", "first");
        CounterEntityIdentity second = new CounterEntityIdentity("post", "second");
        CounterEntityIdentity third = new CounterEntityIdentity("post", "third");
        when(persistenceMapper.findReactionSnapshotIdentityHighWatermark())
                .thenReturn(third);
        when(persistenceMapper.listReactionSnapshotIdentitiesPage(
                null, null, "post", "third", 2))
                .thenReturn(List.of(first, second));
        when(persistenceMapper.listReactionSnapshotIdentitiesPage(
                "post", "second", "post", "third", 2))
                .thenReturn(List.of(third));
        CounterCalibrationService spy = spy(service);
        doThrow(new IllegalStateException("first failed"))
                .when(spy).reconcileEntity("post", "first");
        doThrow(new IllegalStateException("second failed"))
                .when(spy).reconcileEntity("post", "second");
        doReturn(new CounterCalibrationService.ReconciliationResult(1L, 1L, 1L))
                .when(spy).reconcileEntity("post", "third");

        spy.reconcileScheduled();
        spy.reconcileScheduled();

        verify(spy).reconcileEntity("post", "first");
        verify(spy).reconcileEntity("post", "second");
        verify(spy).reconcileEntity("post", "third");
        verify(persistenceMapper).listReactionSnapshotIdentitiesPage(
                "post", "second", "post", "third", 2);
    }

    @Test
    void continuousIdentityGrowthCannotPreventFailedLowIdentitiesFromBeingRevisited() {
        CounterEntityIdentity first = new CounterEntityIdentity("post", "a");
        CounterEntityIdentity second = new CounterEntityIdentity("post", "b");
        CounterEntityIdentity third = new CounterEntityIdentity("post", "c");
        CounterEntityIdentity fifth = new CounterEntityIdentity("post", "e");
        when(persistenceMapper.findReactionSnapshotIdentityHighWatermark())
                .thenReturn(third, fifth);
        when(persistenceMapper.listReactionSnapshotIdentitiesPage(
                null, null, "post", "c", 2))
                .thenReturn(List.of(first, second));
        when(persistenceMapper.listReactionSnapshotIdentitiesPage(
                "post", "b", "post", "c", 2))
                .thenReturn(List.of(third));
        when(persistenceMapper.listReactionSnapshotIdentitiesPage(
                null, null, "post", "e", 2))
                .thenReturn(List.of(first, second));
        CounterCalibrationService spy = spy(service);
        doReturn(new CounterCalibrationService.ReconciliationResult(1L, 1L, 1L))
                .when(spy).reconcileEntity(anyString(), anyString());
        doThrow(new IllegalStateException("first failed"))
                .when(spy).reconcileEntity("post", "a");

        spy.reconcileScheduled();
        spy.reconcileScheduled();
        spy.reconcileScheduled();

        verify(spy, times(2)).reconcileEntity("post", "a");
        verify(spy).reconcileEntity("post", "c");
        verify(persistenceMapper, times(2))
                .findReactionSnapshotIdentityHighWatermark();
    }

    @Test
    void inconsistentSnapshotEpochsFailBeforeRedisRebuild() throws Exception {
        prepareLockAndTransaction("post", "7");
        when(persistenceMapper.lockReactionEpochs("post", "7")).thenReturn(List.of(2L, 3L));

        assertThatThrownBy(() -> service.reconcileEntity("post", "7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("epoch");

        verify(projectionRebuilder, never())
                .rebuild(anyString(), anyString(), anyString(), anyLong());
        verify(projectionRebuilder).abort(eq("post"), eq("7"), anyString());
    }

    @Test
    void maximumEpochCannotWrap() throws Exception {
        prepareLockAndTransaction("post", "7");
        when(persistenceMapper.lockReactionEpochs("post", "7"))
                .thenReturn(List.of(Long.MAX_VALUE, Long.MAX_VALUE));

        assertThatThrownBy(() -> service.reconcileEntity("post", "7"))
                .isInstanceOf(ArithmeticException.class);

        verify(projectionRebuilder, never())
                .rebuild(anyString(), anyString(), anyString(), anyLong());
        verify(projectionRebuilder).abort(eq("post"), eq("7"), anyString());
    }

    private void prepareLockAndTransaction(String entityType, String entityId) throws Exception {
        when(redisson.getLock(CounterKeys.factMaintenanceLockKey(entityType, entityId))).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
    }
}
