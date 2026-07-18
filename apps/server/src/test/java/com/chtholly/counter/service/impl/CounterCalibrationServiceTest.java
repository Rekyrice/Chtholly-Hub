package com.chtholly.counter.service.impl;

import com.chtholly.counter.mapper.CounterEntityIdentity;
import com.chtholly.counter.mapper.CounterPersistenceMapper;
import com.chtholly.counter.schema.CounterKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterCalibrationServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private RedissonClient redisson;
    @Mock
    private RLock lock;
    @Mock
    private CounterPersistenceMapper persistenceMapper;
    @Mock
    private CounterBitmapIndexService bitmapIndex;
    private CounterCalibrationService service;

    @BeforeEach
    void setUp() {
        service = new CounterCalibrationService(
                redis, redisson, persistenceMapper, bitmapIndex, true, 2);
    }

    @Test
    void entityReconciliationPersistsTheBitmapAbsoluteCountsAtTheNewEpoch() throws Exception {
        when(redisson.getLock(CounterKeys.factMaintenanceLockKey("post", "7"))).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(bitmapIndex.requireShardKeys("like", "post", "7")).thenReturn(Set.of());
        when(bitmapIndex.requireShardKeys("fav", "post", "7")).thenReturn(Set.of());
        doAnswer(invocation -> {
            DefaultRedisScript<?> script = invocation.getArgument(0);
            String lua = script.getScriptAsString();
            if (lua.contains("return {likeCount, favCount, nextEpoch}")) {
                return List.of(2L, 3L, 4L);
            }
            return 1L;
        }).when(redis).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));

        service.reconcileEntity("post", "7");

        verify(persistenceMapper).replaceReactionSnapshots("post", "7", 2L, 3L, 4L);
        verify(lock).unlock();
    }

    @Test
    void entityLockIsReleasedWhenRedisFenceReleaseFails() throws Exception {
        when(redisson.getLock(CounterKeys.factMaintenanceLockKey("post", "7"))).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(bitmapIndex.requireShardKeys("like", "post", "7")).thenReturn(Set.of());
        when(bitmapIndex.requireShardKeys("fav", "post", "7")).thenReturn(Set.of());
        doAnswer(invocation -> {
            DefaultRedisScript<?> script = invocation.getArgument(0);
            String lua = script.getScriptAsString();
            if (lua.contains("return {likeCount, favCount, nextEpoch}")) {
                return List.of(2L, 3L, 4L);
            }
            if (lua.contains("return redis.call('DEL', fenceKey)")) {
                throw new IllegalStateException("fence release failed");
            }
            return 1L;
        }).when(redis).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));

        assertThatThrownBy(() -> service.reconcileEntity("post", "7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fence release failed");

        verify(lock).unlock();
    }

    @Test
    void scheduledRunIncludesRedisOnlyAndOldestMysqlCandidates() {
        when(bitmapIndex.discoverCandidates(1))
                .thenReturn(List.of(new CounterEntityIdentity("post", "redis-only")));
        when(bitmapIndex.isBackfillComplete()).thenReturn(true);
        when(persistenceMapper.listOldestReactionSnapshotIdentities(50))
                .thenReturn(List.of(new CounterEntityIdentity("post", "mysql-only")));
        CounterCalibrationService spy = spy(service);
        doReturn(new CounterCalibrationService.ReconciliationResult(1L, 0L, 1L))
                .when(spy).reconcileEntity("post", "redis-only");
        doReturn(new CounterCalibrationService.ReconciliationResult(0L, 0L, 2L))
                .when(spy).reconcileEntity("post", "mysql-only");

        spy.reconcileScheduled();

        verify(spy).reconcileEntity("post", "redis-only");
        verify(spy).reconcileEntity("post", "mysql-only");
        verify(persistenceMapper).listOldestReactionSnapshotIdentities(eq(50));
    }

    @Test
    void scheduledRunReservesCapacityForMysqlOnlyDriftWhenRedisCandidatesFillTheBatch() {
        when(bitmapIndex.discoverCandidates(1))
                .thenReturn(List.of(new CounterEntityIdentity("post", "redis-one")));
        when(bitmapIndex.isBackfillComplete()).thenReturn(true);
        when(persistenceMapper.listOldestReactionSnapshotIdentities(50))
                .thenReturn(List.of(new CounterEntityIdentity("post", "mysql-only")));
        CounterCalibrationService spy = spy(service);
        doReturn(new CounterCalibrationService.ReconciliationResult(1L, 0L, 1L))
                .when(spy).reconcileEntity("post", "redis-one");
        doReturn(new CounterCalibrationService.ReconciliationResult(0L, 0L, 2L))
                .when(spy).reconcileEntity("post", "mysql-only");

        spy.reconcileScheduled();

        verify(spy).reconcileEntity("post", "redis-one");
        verify(spy).reconcileEntity("post", "mysql-only");
        verify(persistenceMapper).listOldestReactionSnapshotIdentities(50);
    }

    @Test
    void persistentBitmapRotationAdvancesDiscoveryAndBoundsEachRun() {
        when(bitmapIndex.discoverCandidates(1)).thenReturn(
                List.of(new CounterEntityIdentity("post", "redis-one")),
                List.of(new CounterEntityIdentity("post", "redis-two")));
        when(bitmapIndex.isBackfillComplete()).thenReturn(true);
        when(persistenceMapper.listOldestReactionSnapshotIdentities(anyInt()))
                .thenReturn(List.of(new CounterEntityIdentity("post", "mysql-only")));
        CounterCalibrationService spy = spy(service);
        for (String entityId : List.of("mysql-only", "redis-one", "redis-two")) {
            doReturn(new CounterCalibrationService.ReconciliationResult(0L, 0L, 1L))
                    .when(spy).reconcileEntity("post", entityId);
        }

        spy.reconcileScheduled();
        spy.reconcileScheduled();

        verify(spy, times(2)).reconcileEntity("post", "mysql-only");
        verify(spy).reconcileEntity("post", "redis-one");
        verify(spy).reconcileEntity("post", "redis-two");
        verify(bitmapIndex, times(2)).discoverCandidates(1);
        verify(bitmapIndex).rotateCandidate(new CounterEntityIdentity("post", "redis-one"));
        verify(bitmapIndex).rotateCandidate(new CounterEntityIdentity("post", "redis-two"));
    }

    @Test
    void failedOldestMysqlCandidateDoesNotStarveTheNextSnapshot() {
        CounterCalibrationService singleSlotService =
                new CounterCalibrationService(
                        redis, redisson, persistenceMapper, bitmapIndex, true, 1);
        when(bitmapIndex.discoverCandidates(1)).thenReturn(List.of());
        when(bitmapIndex.isBackfillComplete()).thenReturn(true);
        when(persistenceMapper.listOldestReactionSnapshotIdentities(anyInt())).thenReturn(List.of(
                new CounterEntityIdentity("post", "stuck"),
                new CounterEntityIdentity("post", "next")));
        CounterCalibrationService spy = spy(singleSlotService);
        doThrow(new IllegalStateException("persistent failure"))
                .when(spy).reconcileEntity("post", "stuck");
        doReturn(new CounterCalibrationService.ReconciliationResult(0L, 0L, 1L))
                .when(spy).reconcileEntity("post", "next");

        spy.reconcileScheduled();
        spy.reconcileScheduled();

        verify(spy).reconcileEntity("post", "stuck");
        verify(spy).reconcileEntity("post", "next");
        verify(persistenceMapper, times(2)).listOldestReactionSnapshotIdentities(50);
    }

    @Test
    void scheduledRunAdvancesOnlyDiscoveryWhileInitialBackfillIsIncomplete() {
        when(bitmapIndex.discoverCandidates(1)).thenReturn(List.of());
        when(bitmapIndex.isBackfillComplete()).thenReturn(false);

        service.reconcileScheduled();

        verify(bitmapIndex).discoverCandidates(1);
        verify(persistenceMapper, org.mockito.Mockito.never())
                .listOldestReactionSnapshotIdentities(anyInt());
        verify(bitmapIndex, org.mockito.Mockito.never()).rotateCandidate(any());
    }
}
