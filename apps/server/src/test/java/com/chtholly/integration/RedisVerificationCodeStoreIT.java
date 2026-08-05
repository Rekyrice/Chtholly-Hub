package com.chtholly.integration;

import com.chtholly.auth.verification.RedisVerificationCodeStore;
import com.chtholly.auth.verification.VerificationCheckResult;
import com.chtholly.auth.verification.VerificationCodeStatus;
import com.chtholly.auth.verification.VerificationCodeStore;
import com.chtholly.auth.verification.VerificationScene;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Verifies verification-code atomicity against a real Redis script engine. */
@Testcontainers(disabledWithoutDocker = true)
class RedisVerificationCodeStoreIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisVerificationCodeStore store;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(
                        REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        store = new RedisVerificationCodeStore(redis);
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void clearRedis() {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    @Test
    void concurrentCorrectAttemptsHaveExactlyOneWinner() throws Exception {
        save("123456", "generation-1", 5);

        List<VerificationCodeStatus> outcomes = runConcurrently(
                () -> store.verify(scene(), identifier(), "123456").status(),
                () -> store.verify(scene(), identifier(), "123456").status());

        assertThat(outcomes).containsExactlyInAnyOrder(
                VerificationCodeStatus.SUCCESS,
                VerificationCodeStatus.NOT_FOUND);
    }

    @Test
    void concurrentFailuresReachTheExactAttemptLimit() throws Exception {
        save("123456", "generation-1", 2);

        List<VerificationCodeStatus> outcomes = runConcurrently(
                () -> store.verify(scene(), identifier(), "000000").status(),
                () -> store.verify(scene(), identifier(), "111111").status());

        assertThat(outcomes).containsExactlyInAnyOrder(
                VerificationCodeStatus.MISMATCH,
                VerificationCodeStatus.TOO_MANY_ATTEMPTS);
        VerificationCheckResult locked =
                store.verify(scene(), identifier(), "123456");
        assertThat(locked.status())
                .isEqualTo(VerificationCodeStatus.TOO_MANY_ATTEMPTS);
        assertThat(locked.attempts()).isEqualTo(2);
    }

    @Test
    void staleDeliveryFailureCannotDeleteANewerCode() {
        save("111111", "generation-1", 5);
        save("222222", "generation-2", 5);

        assertThat(store.invalidateIfCurrent(
                scene(), identifier(), "generation-1")).isFalse();
        assertThat(store.verify(scene(), identifier(), "222222").status())
                .isEqualTo(VerificationCodeStatus.SUCCESS);
    }

    @Test
    void saveCreatesOneCompleteExpiringRecord() {
        save("123456", "generation-1", 5);

        String key = redis.keys("auth:code:{*}").stream().findFirst().orElseThrow();
        assertThat(redis.opsForHash().entries(key))
                .containsEntry("code", "123456")
                .containsEntry("version", "generation-1")
                .containsEntry("attempts", "0")
                .containsEntry("maxAttempts", "5");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofMinutes(5).toMillis());
    }

    @Test
    void expiredCodeIsNoLongerUsable() {
        store.saveCode(
                scene(),
                identifier(),
                new VerificationCodeStore.IssuedCode("123456", "generation-1"),
                Duration.ofMillis(50),
                5);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(store.verify(scene(), identifier(), "123456").status())
                        .isEqualTo(VerificationCodeStatus.NOT_FOUND));
    }

    @Test
    void consumingANewCodeCannotReactivateAStillLiveLegacyCode() {
        String legacyKey = "auth:code:" + scene() + ":" + identifier();
        redis.opsForHash().put(legacyKey, "code", "111111");
        redis.opsForHash().put(legacyKey, "version", "legacy-generation");
        redis.opsForHash().put(legacyKey, "attempts", "0");
        redis.opsForHash().put(legacyKey, "maxAttempts", "5");
        redis.expire(legacyKey, Duration.ofMinutes(5));

        save("222222", "generation-2", 5);
        assertThat(store.verify(scene(), identifier(), "222222").status())
                .isEqualTo(VerificationCodeStatus.SUCCESS);

        assertThat(store.verify(scene(), identifier(), "111111").status())
                .isEqualTo(VerificationCodeStatus.NOT_FOUND);
        assertThat(redis.hasKey(legacyKey)).isTrue();
    }

    private static void save(String code, String version, int maxAttempts) {
        store.saveCode(
                scene(),
                identifier(),
                new VerificationCodeStore.IssuedCode(code, version),
                Duration.ofMinutes(5),
                maxAttempts);
    }

    private static String scene() {
        return VerificationScene.LOGIN.name();
    }

    private static String identifier() {
        return "concurrent@example.com";
    }

    private static <T> List<T> runConcurrently(
            java.util.concurrent.Callable<T> firstTask,
            java.util.concurrent.Callable<T> secondTask) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<T> first = executor.submit(() ->
                    afterBarrier(firstTask, ready, start));
            Future<T> second = executor.submit(() ->
                    afterBarrier(secondTask, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> T afterBarrier(
            java.util.concurrent.Callable<T> task,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("verification barrier timed out");
        }
        return task.call();
    }
}
