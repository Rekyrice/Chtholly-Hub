package com.chtholly.cache.singleflight;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class SingleFlightLockRegistryTest {

    @Test
    void removesLockAfterException() {
        SingleFlightLockRegistry registry = new SingleFlightLockRegistry();

        assertThatThrownBy(() -> registry.runExclusive("detail:1", () -> {
            throw new IllegalStateException("db failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(registry.registeredCount()).isZero();
    }

    @Test
    void concurrentSameKeyRunsActionOnceWithDoubleCheck() throws Exception {
        SingleFlightLockRegistry registry = new SingleFlightLockRegistry();
        AtomicInteger dbCalls = new AtomicInteger();
        AtomicReference<String> cache = new AtomicReference<>();
        CountDownLatch leaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLeader = new CountDownLatch(1);
        List<Thread> workerThreads = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(10, runnable -> {
            Thread thread = new Thread(runnable);
            workerThreads.add(thread);
            return thread;
        });
        List<Future<String>> futures = new ArrayList<>();

        Callable<String> worker = () -> registry.runExclusive("feed:page:1", () -> {
            String hit = cache.get();
            if (hit != null) {
                return hit;
            }
            dbCalls.incrementAndGet();
            leaderEntered.countDown();
            try {
                if (!releaseLeader.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("leader was not released in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while holding SingleFlight lock", e);
            }
            cache.set("loaded");
            return "loaded";
        });

        try {
            futures.add(executor.submit(worker));
            assertThat(leaderEntered.await(5, TimeUnit.SECONDS)).isTrue();

            for (int i = 0; i < 9; i++) {
                futures.add(executor.submit(worker));
            }

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(workerThreads.stream()
                            .filter(thread -> thread.getState() == Thread.State.BLOCKED)
                            .count()).isGreaterThanOrEqualTo(9));

            releaseLeader.countDown();
            for (Future<String> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("loaded");
            }

            assertThat(dbCalls.get()).isEqualTo(1);
            assertThat(registry.registeredCount()).isZero();
        } finally {
            releaseLeader.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void differentKeysRunInParallel() throws Exception {
        SingleFlightLockRegistry registry = new SingleFlightLockRegistry();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch enteredActions = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Void> first = executor.submit(worker(registry, calls, enteredActions, "a"));
            Future<Void> second = executor.submit(worker(registry, calls, enteredActions, "b"));

            assertThat(first.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(calls.get()).isEqualTo(2);
            assertThat(registry.registeredCount()).isZero();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Callable<Void> worker(SingleFlightLockRegistry registry, AtomicInteger calls,
                                         CountDownLatch enteredActions, String key) {
        return () -> registry.runExclusive(key, () -> {
            enteredActions.countDown();
            try {
                if (!enteredActions.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("different keys were serialized");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for actions", e);
            }
            calls.incrementAndGet();
            return null;
        });
    }
}
