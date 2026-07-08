package com.chtholly.common.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedLockServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private DistributedLockService lockService;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        lockService = new DistributedLockService(redis, "instance-a");
    }

    @Test
    void tryLockSetsHolderWithTtlAndUnlockDeletesOnlyOwnedLock() {
        Duration ttl = Duration.ofMinutes(5);
        when(valueOps.setIfAbsent(eq("lock:scheduled:test"), anyString(), eq(ttl))).thenReturn(true);
        when(valueOps.get("lock:scheduled:test")).thenAnswer(invocation -> lockService.localHolder("lock:scheduled:test"));

        boolean locked = lockService.tryLock("lock:scheduled:test", ttl);
        lockService.unlock("lock:scheduled:test");

        assertThat(locked).isTrue();
        verify(valueOps).setIfAbsent(eq("lock:scheduled:test"), anyString(), eq(ttl));
        verify(redis).delete("lock:scheduled:test");
    }

    @Test
    void tryLockReturnsFalseWhenAnotherInstanceHoldsLock() {
        when(valueOps.setIfAbsent(eq("lock:scheduled:test"), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(false);

        boolean locked = lockService.tryLock("lock:scheduled:test", Duration.ofSeconds(30));

        assertThat(locked).isFalse();
        assertThat(lockService.localHolder("lock:scheduled:test")).isNull();
    }

    @Test
    void nonHolderCannotReleaseLock() {
        lockService.unlock("lock:scheduled:foreign");

        verify(redis, never()).delete("lock:scheduled:foreign");
    }

    @Test
    void holderMismatchDoesNotDeleteRedisKey() {
        when(valueOps.setIfAbsent(eq("lock:scheduled:test"), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(true);
        when(valueOps.get("lock:scheduled:test")).thenReturn("other-instance:42");

        lockService.tryLock("lock:scheduled:test", Duration.ofSeconds(30));
        lockService.unlock("lock:scheduled:test");

        verify(redis, never()).delete("lock:scheduled:test");
    }

    @Test
    void concurrentTryLockAllowsOnlyOneWinner() throws Exception {
        AtomicBoolean claimed = new AtomicBoolean(false);
        when(valueOps.setIfAbsent(eq("lock:scheduled:test"), anyString(), eq(Duration.ofSeconds(30))))
                .thenAnswer(invocation -> claimed.compareAndSet(false, true));
        DistributedLockService first = new DistributedLockService(redis, "instance-a");
        DistributedLockService second = new DistributedLockService(redis, "instance-b");
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        List<Boolean> results = new ArrayList<>();

        executor.submit(() -> {
            await(start);
            synchronized (results) {
                results.add(first.tryLock("lock:scheduled:test", Duration.ofSeconds(30)));
            }
        });
        executor.submit(() -> {
            await(start);
            synchronized (results) {
                results.add(second.tryLock("lock:scheduled:test", Duration.ofSeconds(30)));
            }
        });
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(results).containsExactlyInAnyOrder(true, false);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
