package com.chtholly.cache.singleflight;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        java.util.concurrent.atomic.AtomicReference<String> cache = new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(10);

        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    registry.runExclusive("feed:page:1", () -> {
                        String hit = cache.get();
                        if (hit != null) {
                            return hit;
                        }
                        dbCalls.incrementAndGet();
                        try {
                            Thread.sleep(80);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        cache.set("loaded");
                        return "loaded";
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(dbCalls.get()).isEqualTo(1);
        assertThat(registry.registeredCount()).isZero();
    }

    @Test
    void differentKeysRunInParallel() throws Exception {
        SingleFlightLockRegistry registry = new SingleFlightLockRegistry();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        new Thread(worker(registry, calls, start, done, "a")).start();
        new Thread(worker(registry, calls, start, done, "b")).start();

        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(registry.registeredCount()).isZero();
    }

    private static Runnable worker(SingleFlightLockRegistry registry, AtomicInteger calls,
                                   CountDownLatch start, CountDownLatch done, String key) {
        return () -> {
            try {
                start.await();
                registry.runExclusive(key, () -> {
                    calls.incrementAndGet();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };
    }
}
