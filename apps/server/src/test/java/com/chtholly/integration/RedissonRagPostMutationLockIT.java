package com.chtholly.integration;

import com.chtholly.llm.rag.RedissonRagPostMutationLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies cross-client serialization and watchdog renewal for RAG mutations. */
@Testcontainers(disabledWithoutDocker = true)
class RedissonRagPostMutationLockIT {

    private static final int REDIS_PORT = 6379;
    private static final long TEST_WATCHDOG_MILLIS = 1_200L;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    private RedissonClient firstClient;
    private RedissonClient secondClient;

    @BeforeEach
    void setUpClients() {
        firstClient = Redisson.create(config());
        secondClient = Redisson.create(config());
    }

    @AfterEach
    void shutDownClients() {
        if (firstClient != null) {
            firstClient.shutdown();
        }
        if (secondClient != null) {
            secondClient.shutdown();
        }
    }

    @Test
    void samePostMutationsRemainSerializedBeyondInitialWatchdogLease()
            throws Exception {
        RedissonRagPostMutationLock firstLock =
                new RedissonRagPostMutationLock(firstClient);
        RedissonRagPostMutationLock secondLock =
                new RedissonRagPostMutationLock(secondClient);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() ->
                    firstLock.withLock(42L, () -> {
                        firstEntered.countDown();
                        awaitLatch(releaseFirst);
                        return 1;
                    }));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Integer> second = executor.submit(() ->
                    secondLock.withLock(42L, () -> {
                        secondEntered.countDown();
                        return 2;
                    }));

            assertThat(secondEntered.await(
                    Duration.ofMillis(TEST_WATCHDOG_MILLIS + 600L).toMillis(),
                    TimeUnit.MILLISECONDS)).isFalse();
            releaseFirst.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(secondEntered.getCount()).isZero();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static Config config() {
        Config config = new Config();
        config.setLockWatchdogTimeout(TEST_WATCHDOG_MILLIS);
        config.useSingleServer().setAddress(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT));
        return config;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("RAG lock test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RAG lock test interrupted", interrupted);
        }
    }
}
