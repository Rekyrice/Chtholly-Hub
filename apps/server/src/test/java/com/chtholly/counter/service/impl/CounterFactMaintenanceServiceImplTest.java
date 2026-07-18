package com.chtholly.counter.service.impl;

import com.chtholly.counter.service.CounterFactMaintenanceService;
import com.chtholly.counter.service.CounterFactMaintenanceService.ManagedPostReactionState;
import com.chtholly.counter.service.CounterFactMaintenanceService.PostReactionReconciliationResult;
import com.chtholly.counter.service.CounterFactMaintenanceService.ReactionReconciliationResult;
import com.chtholly.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
class CounterFactMaintenanceServiceImplTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private RedissonClient redisson;
    @Mock
    private UserMapper userMapper;
    @Mock
    private RedisConnection connection;
    @Mock
    private RedisStringCommands stringCommands;
    @Mock
    private RLock lock;

    private CounterFactMaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new CounterFactMaintenanceServiceImpl(redis, redisson, userMapper);
        lenient().doReturn(1L).when(redis).execute(
                argThat((RedisScript script) -> script != null && Long.class.equals(script.getResultType())),
                anyList(), any(Object[].class));
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
                Set.of(1L), Set.of(10L), Map.of(11L, new ManagedPostReactionState(Set.of(), Set.of()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(10L), Map.of(10L, new ManagedPostReactionState(Set.of(2L), Set.of()))))
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

        verifyNoInteractions(redis, redisson, userMapper);
    }

    @Test
    void decodesRedisMsbFirstBitmapForHighSnowflakeUserId() {
        long userId = 888_888_888_888_888_888L;
        long chunk = userId / 32_768L;
        int bit = (int) (userId % 32_768L);
        byte[] raw = new byte[(bit / 8) + 1];
        raw[bit / 8] = (byte) (1 << (7 - (bit % 8)));

        assertThat(CounterFactMaintenanceServiceImpl.decodeSetUserIds(chunk, raw))
                .containsExactly(userId);
    }

    @Test
    void rejectsBitmapChunkOverflowBeforeAnyWrite() {
        long overflowingChunk = Math.floorDiv(Long.MAX_VALUE, 32_768L) + 1L;

        assertThatThrownBy(() -> CounterFactMaintenanceServiceImpl.decodeSetUserIds(
                overflowingChunk, new byte[]{(byte) 0x80}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supported user ID range");
    }

    @Test
    void usesScanAndRawBinaryGetPreservesExistingNaturalUserAndPlansOrphanClear() throws Exception {
        String likeKey = "bm:like:post:10:0";
        stubScans(List.of(likeKey), List.of());
        stubRawBitmaps(Map.of(likeKey, bitmapBytes(1L, 5L, 7L)));
        when(userMapper.listExistingIds(List.of(5L, 7L))).thenReturn(List.of(5L));
        stubSuccessfulLock(10L);
        stubLuaResults(List.of(1L, 1L, 1L, 2L, 0L));

        ReactionReconciliationResult result = service.reconcileManagedPostReactions(
                Set.of(1L, 2L),
                Set.of(10L),
                Map.of(10L, new ManagedPostReactionState(Set.of(2L), Set.of())));

        assertThat(result.posts()).containsEntry(10L,
                new PostReactionReconciliationResult(10L, 1L, 1L, 1L, 2L, 0L));
        ArgumentCaptor<ScanOptions> scanOptions = ArgumentCaptor.forClass(ScanOptions.class);
        verify(redis, times(2)).scan(scanOptions.capture());
        assertThat(scanOptions.getAllValues())
                .extracting(ScanOptions::getPattern)
                .containsExactly("bm:like:post:10:*", "bm:fav:post:10:*");
        verify(redis, never()).keys(anyString());
        verify(redis, never()).opsForValue();
        verify(stringCommands).get(likeKey.getBytes(StandardCharsets.UTF_8));
        verify(userMapper).listExistingIds(List.of(5L, 7L));

        CapturedLua invocation = captureOnlyLuaInvocation();
        assertThat(invocation.keys()).contains(
                "cnt:v1:post:10",
                "agg:v1:post:10",
                "agg:v1:__keys",
                "counter:fact-maintenance:post:10",
                "counter:fact-epoch:post:10",
                likeKey,
                "bm:fav:post:10:0");
        assertThat(invocation.arguments()).startsWith(invocation.arguments().getFirst(),
                "20", "4", "1", "2", "2", "like", "fav");
        assertThat(invocation.arguments()).containsSubsequence("6", "7", "0", "orphan");
        assertThat(invocation.arguments()).endsWith("6", "7", "0", "orphan");
        assertThat(invocation.arguments()).filteredOn("orphan"::equals).hasSize(1);
        verify(lock).unlock();
    }

    @Test
    void secondReconciliationWithSameDesiredStateReportsNoBitChanges() throws Exception {
        stubScans(List.of(), List.of(), List.of(), List.of());
        when(redisson.getLock("lock:counter-fact-maintenance:post:10")).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.MILLISECONDS)).thenReturn(true, true);
        doReturn(List.of(2L, 2L, 0L, 1L, 1L), List.of(0L, 0L, 0L, 1L, 1L))
                .when(redis).execute(
                        argThat((RedisScript script) -> script != null && List.class.equals(script.getResultType())),
                        anyList(), any(Object[].class));

        ReactionReconciliationResult first = service.reconcileManagedPostReactions(
                Set.of(1L, 2L), Set.of(10L),
                Map.of(10L, new ManagedPostReactionState(Set.of(1L), Set.of(2L))));
        ReactionReconciliationResult second = service.reconcileManagedPostReactions(
                Set.of(1L, 2L), Set.of(10L),
                Map.of(10L, new ManagedPostReactionState(Set.of(1L), Set.of(2L))));

        assertThat(first.posts().get(10L).managedSetCount()).isEqualTo(2L);
        assertThat(first.posts().get(10L).managedClearCount()).isEqualTo(2L);
        assertThat(second.posts().get(10L).managedSetCount()).isZero();
        assertThat(second.posts().get(10L).managedClearCount()).isZero();
        verify(redis, times(2)).execute(
                argThat((RedisScript script) -> script != null && List.class.equals(script.getResultType())),
                anyList(), any(Object[].class));
    }

    @Test
    void userLookupFailureInsideFencePreventsReconciliationWriteAndReleasesOwnership() throws Exception {
        String likeKey = "bm:like:post:10:0";
        stubScans(List.of(likeKey), List.of());
        stubRawBitmaps(Map.of(likeKey, bitmapBytes(5L)));
        when(userMapper.listExistingIds(List.of(5L))).thenThrow(new IllegalStateException("mysql unavailable"));
        stubSuccessfulLock(10L);

        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(10L), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql unavailable");

        verifyNoReconciliationWrite();
        verify(lock).unlock();
    }

    @Test
    void scansEveryShardAndBatchesNonManagedUserExistenceChecks() throws Exception {
        long highUserId = 888_888_888_888_888_888L;
        long highChunk = highUserId / 32_768L;
        String lowKey = "bm:like:post:10:0";
        String highKey = "bm:like:post:10:" + highChunk;
        stubScans(List.of(lowKey, highKey), List.of());
        stubRawBitmaps(Map.of(
                lowKey, bitmapBytes(range(2L, 502L)),
                highKey, bitmapBytesWithinChunk(highUserId)));
        when(userMapper.listExistingIds(anyList())).thenReturn(List.of());
        stubSuccessfulLock(10L);
        stubLuaResults(List.of(0L, 2L, 501L, 0L, 0L));

        service.reconcileManagedPostReactions(Set.of(1L), Set.of(10L), Map.of());

        ArgumentCaptor<List<Long>> batches = ArgumentCaptor.forClass(List.class);
        verify(userMapper, times(2)).listExistingIds(batches.capture());
        assertThat(batches.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(500));
        assertThat(batches.getAllValues().stream().flatMap(List::stream))
                .contains(highUserId)
                .hasSize(501);
        verify(stringCommands).get(lowKey.getBytes(StandardCharsets.UTF_8));
        verify(stringCommands).get(highKey.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void failureInLaterUserLookupBatchStillPreventsEveryPostWrite() throws Exception {
        String likeKey = "bm:like:post:10:0";
        stubScans(List.of(likeKey), List.of());
        stubRawBitmaps(Map.of(likeKey, bitmapBytes(range(2L, 503L))));
        when(userMapper.listExistingIds(anyList()))
                .thenReturn(List.of())
                .thenThrow(new IllegalStateException("second batch failed"));
        stubSuccessfulLock(10L);

        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(10L), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("second batch failed");

        verify(userMapper, times(2)).listExistingIds(anyList());
        verifyNoReconciliationWrite();
        verify(lock).unlock();
    }

    @Test
    void laterPostLockFailureLeavesEarlierPostCommittedWithoutClaimingBatchAtomicity() throws Exception {
        RLock secondLock = mock(RLock.class);
        stubScans(List.of(), List.of());
        when(redisson.getLock("lock:counter-fact-maintenance:post:10")).thenReturn(lock);
        when(redisson.getLock("lock:counter-fact-maintenance:post:20")).thenReturn(secondLock);
        when(lock.tryLock(0L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(secondLock.tryLock(0L, TimeUnit.MILLISECONDS)).thenReturn(false);
        stubLuaResults(List.of(0L, 2L, 0L, 0L, 0L));

        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(20L, 10L), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("20");

        verify(redis).execute(
                argThat((RedisScript script) -> script != null && List.class.equals(script.getResultType())),
                anyList(), any(Object[].class));
        verify(lock).unlock();
        verify(secondLock, never()).unlock();
    }

    @Test
    void lockAcquisitionFailureThrowsWithoutLuaWrite() throws Exception {
        when(redisson.getLock("lock:counter-fact-maintenance:post:10")).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.MILLISECONDS)).thenReturn(false);

        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(10L), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10");

        verify(redis, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
        verify(lock, never()).unlock();
    }

    @Test
    void interruptedLockAcquisitionRestoresInterruptAndDoesNotWrite() throws Exception {
        when(redisson.getLock("lock:counter-fact-maintenance:post:10")).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException("stop"));

        try {
            assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                    Set.of(1L), Set.of(10L), Map.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(redis, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void luaAtomicallyUpdatesBitsCountsShardsPreservesSdsAndCleansReactionAggregation() throws Exception {
        stubScans(List.of(), List.of());
        stubSuccessfulLock(10L);
        stubLuaResults(List.of(2L, 0L, 0L, 1L, 1L));

        service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(10L),
                Map.of(10L, new ManagedPostReactionState(Set.of(1L), Set.of(1L))));

        CapturedLua invocation = captureOnlyLuaInvocation();
        String lua = invocation.script().getScriptAsString();
        assertThat(lua).contains(
                "redis.call('TYPE', key)",
                "redis.call('GETBIT'",
                "redis.call('SETBIT'",
                "redis.call('BITCOUNT'",
                "redis.call('GET', cntKey)",
                "string.len(raw) ~= expectedLength",
                "redis.call('SET', cntKey, raw)",
                "redis.call('HDEL', aggKey, tostring(likeIndex), tostring(favIndex))",
                "redis.call('HLEN', aggKey) == 0",
                "redis.call('SREM', aggIndexKey, aggKey)",
                "redis.call('DEL', bitmapKey)",
                "redis.call('GET', fenceKey) ~= expectedToken",
                "redis.call('INCR', epochKey)");
        assertThat(lua.indexOf("redis.call('TYPE', key)")).isLessThan(lua.indexOf("redis.call('SETBIT'"));
        assertThat(lua.indexOf("redis.call('GET', cntKey)")).isLessThan(lua.indexOf("redis.call('SETBIT'"));
        assertThat(lua.indexOf("redis.call('GET', fenceKey) ~= expectedToken"))
                .isLessThan(lua.indexOf("redis.call('GETBIT'"));
        assertThat(lua.indexOf("redis.call('INCR', epochKey)"))
                .isLessThan(lua.indexOf("redis.call('SETBIT'"));
        assertThat(lua).contains(
                "return string.sub(value, 1, offset)",
                ".. string.sub(value, offset + fieldSize + 1)",
                "4294967295");
    }

    @Test
    void maintenanceFenceIsEstablishedBeforeSnapshotAndIdOnlyExistenceLookup() throws Exception {
        String likeKey = "bm:like:post:10:0";
        stubScans(List.of(likeKey), List.of());
        stubRawBitmaps(Map.of(likeKey, bitmapBytes(5L)));
        when(userMapper.listExistingIds(List.of(5L))).thenReturn(List.of(5L));
        stubSuccessfulLock(10L);
        stubLuaResults(List.of(0L, 2L, 0L, 1L, 0L));

        service.reconcileManagedPostReactions(Set.of(1L), Set.of(10L), Map.of());

        InOrder order = inOrder(lock, redis, userMapper);
        order.verify(lock).tryLock(0L, TimeUnit.MILLISECONDS);
        order.verify(redis).execute(
                argThat((RedisScript script) -> script != null && Long.class.equals(script.getResultType())
                        && script.getScriptAsString().contains("leaseMillis")),
                anyList(), any(Object[].class));
        order.verify(redis, times(2)).scan(any(ScanOptions.class));
        order.verify(userMapper).listExistingIds(List.of(5L));
        order.verify(redis).execute(
                argThat((RedisScript script) -> script != null && List.class.equals(script.getResultType())),
                anyList(), any(Object[].class));
    }

    @Test
    void cleanupFailureDoesNotMaskPrimaryLookupFailure() throws Exception {
        String likeKey = "bm:like:post:10:0";
        stubScans(List.of(likeKey), List.of());
        stubRawBitmaps(Map.of(likeKey, bitmapBytes(5L)));
        when(userMapper.listExistingIds(List.of(5L)))
                .thenThrow(new IllegalStateException("mysql unavailable"));
        stubSuccessfulLock(10L);
        doThrow(new IllegalStateException("fence release failed")).when(redis).execute(
                argThat((RedisScript script) -> script != null && Long.class.equals(script.getResultType())
                        && script.getScriptAsString().contains("return redis.call('DEL', fenceKey)")),
                anyList(), any(Object[].class));

        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(1L), Set.of(10L), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql unavailable")
                .satisfies(error -> assertThat(error.getSuppressed())
                        .extracting(Throwable::getMessage)
                        .containsExactly("fence release failed"));
    }

    private void stubScans(List<String>... scans) {
        Cursor<String>[] cursors = Arrays.stream(scans)
                .map(this::cursor)
                .toArray(Cursor[]::new);
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursors[0], Arrays.copyOfRange(cursors, 1, cursors.length));
    }

    private Cursor<String> cursor(List<String> keys) {
        Cursor<String> cursor = mock(Cursor.class);
        AtomicInteger index = new AtomicInteger();
        when(cursor.hasNext()).thenAnswer(invocation -> index.get() < keys.size());
        lenient().when(cursor.next()).thenAnswer(invocation -> keys.get(index.getAndIncrement()));
        return cursor;
    }

    private void stubRawBitmaps(Map<String, byte[]> bitmaps) {
        when(connection.stringCommands()).thenReturn(stringCommands);
        when(stringCommands.get(any(byte[].class))).thenAnswer(invocation ->
                bitmaps.get(new String(invocation.getArgument(0), StandardCharsets.UTF_8)));
        doAnswer(invocation -> ((RedisCallback<?>) invocation.getArgument(0)).doInRedis(connection))
                .when(redis).execute(any(RedisCallback.class));
    }

    private void stubSuccessfulLock(long postId) throws Exception {
        when(redisson.getLock("lock:counter-fact-maintenance:post:" + postId)).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.MILLISECONDS)).thenReturn(true);
    }

    private void stubLuaResults(List<Long> result) {
        doReturn(result).when(redis)
                .execute(argThat((RedisScript script) -> script != null && List.class.equals(script.getResultType())),
                        anyList(), any(Object[].class));
    }

    private CapturedLua captureOnlyLuaInvocation() {
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        AtomicReference<DefaultRedisScript<List>> script = new AtomicReference<>();
        verify(redis).execute(
                argThat((RedisScript candidate) -> {
                    if (candidate == null || !List.class.equals(candidate.getResultType())) {
                        return false;
                    }
                    script.set((DefaultRedisScript<List>) (Object) candidate);
                    return true;
                }),
                keys.capture(), arguments.capture());
        return new CapturedLua(script.get(), keys.getValue(),
                Arrays.stream(arguments.getValue()).map(String::valueOf).toList());
    }

    private void verifyNoReconciliationWrite() {
        verify(redis, never()).execute(
                argThat((RedisScript script) -> script != null && List.class.equals(script.getResultType())),
                anyList(), any(Object[].class));
    }

    private static byte[] bitmapBytes(long... bitOffsets) {
        return bitmapBytesWithinChunk(bitOffsets);
    }

    private static byte[] bitmapBytesWithinChunk(long... userIds) {
        int maxBit = Arrays.stream(userIds)
                .mapToInt(userId -> (int) (userId % 32_768L))
                .max()
                .orElse(0);
        byte[] raw = new byte[(maxBit / Byte.SIZE) + 1];
        for (long userId : userIds) {
            int bit = (int) (userId % 32_768L);
            raw[bit / Byte.SIZE] |= (byte) (1 << (7 - (bit % Byte.SIZE)));
        }
        return raw;
    }

    private static long[] range(long startInclusive, long endExclusive) {
        List<Long> values = new ArrayList<>();
        for (long value = startInclusive; value < endExclusive; value++) {
            values.add(value);
        }
        return values.stream().mapToLong(Long::longValue).toArray();
    }

    private record CapturedLua(
            DefaultRedisScript<List> script,
            List<String> keys,
            List<String> arguments) {}
}
