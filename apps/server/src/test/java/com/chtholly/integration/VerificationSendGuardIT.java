package com.chtholly.integration;

import com.chtholly.auth.verification.LegacyVerificationQuotaReader;
import com.chtholly.auth.verification.VerificationScene;
import com.chtholly.auth.verification.VerificationSendGuard;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies atomic verification-code quota reservations against Redis. */
@Testcontainers(disabledWithoutDocker = true)
class VerificationSendGuardIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static VerificationSendGuard guard;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(
                        REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        guard = new VerificationSendGuard(
                redis, new LegacyVerificationQuotaReader(redis));
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
    void concurrentIntervalReservationsHaveExactlyOneWinner() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ErrorCode> first = executor.submit(() ->
                    reserveAfterBarrier(ready, start));
            Future<ErrorCode> second = executor.submit(() ->
                    reserveAfterBarrier(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ErrorCode> outcomes = new ArrayList<>();
            outcomes.add(first.get());
            outcomes.add(second.get());
            assertThat(outcomes)
                    .containsExactlyInAnyOrder(null, ErrorCode.VERIFICATION_RATE_LIMIT);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void compensationRestoresDailyQuota() {
        VerificationSendGuard.Reservation reservation = guard.reserve(
                VerificationScene.LOGIN,
                "quota@example.com",
                Duration.ZERO,
                1);

        guard.compensate(reservation);

        guard.reserve(
                VerificationScene.LOGIN,
                "quota@example.com",
                Duration.ZERO,
                1);
    }

    @Test
    void dailyRejectionDoesNotLeaveAnIntervalReservation() {
        VerificationSendGuard.Reservation first = guard.reserve(
                VerificationScene.LOGIN,
                "daily@example.com",
                Duration.ZERO,
                1);

        assertThatThrownBy(() -> guard.reserve(
                VerificationScene.LOGIN,
                "daily@example.com",
                Duration.ofMinutes(1),
                1))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VERIFICATION_DAILY_LIMIT));

        guard.compensate(first);
        guard.reserve(
                VerificationScene.LOGIN,
                "daily@example.com",
                Duration.ofMinutes(1),
                1);
    }

    @Test
    void liveLegacyIntervalStillRejectsDuringKeyMigration() {
        redis.opsForValue().set(
                "auth:code:last:LOGIN:legacy@example.com",
                "1",
                Duration.ofMinutes(1));

        assertThatThrownBy(() -> guard.reserve(
                VerificationScene.LOGIN,
                "legacy@example.com",
                Duration.ofMinutes(1),
                10))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VERIFICATION_RATE_LIMIT));
    }

    @Test
    void legacyDailyCountSeedsTheNewAtomicCounter() {
        String day = DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now());
        redis.opsForValue().set(
                "auth:code:count:LOGIN:legacy-daily@example.com:" + day,
                "2",
                Duration.ofDays(1));

        guard.reserve(
                VerificationScene.LOGIN,
                "legacy-daily@example.com",
                Duration.ZERO,
                3);

        assertThatThrownBy(() -> guard.reserve(
                VerificationScene.LOGIN,
                "legacy-daily@example.com",
                Duration.ZERO,
                3))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VERIFICATION_DAILY_LIMIT));
    }

    @Test
    void staleCompensationDoesNotDeleteANewerIntervalOwner() {
        VerificationSendGuard.Reservation reservation = guard.reserve(
                VerificationScene.LOGIN,
                "nonce@example.com",
                Duration.ofMinutes(1),
                0);
        String intervalKey = redis.keys(
                        "auth:code:quota:{*}:interval")
                .stream()
                .findFirst()
                .orElseThrow();
        redis.opsForValue().set(intervalKey, "newer-owner", Duration.ofMinutes(1));

        guard.compensate(reservation);

        assertThat(redis.opsForValue().get(intervalKey)).isEqualTo("newer-owner");
        assertThatThrownBy(() -> guard.reserve(
                VerificationScene.LOGIN,
                "nonce@example.com",
                Duration.ofMinutes(1),
                0))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VERIFICATION_RATE_LIMIT));
    }

    @Test
    void concurrentCompensationDecrementsDailyQuotaOnlyOnce() throws Exception {
        VerificationSendGuard.Reservation failed = guard.reserve(
                VerificationScene.LOGIN,
                "compensate@example.com",
                Duration.ZERO,
                3);
        guard.reserve(
                VerificationScene.LOGIN,
                "compensate@example.com",
                Duration.ZERO,
                3);

        runConcurrentCompensation(failed);

        guard.reserve(
                VerificationScene.LOGIN,
                "compensate@example.com",
                Duration.ZERO,
                3);
        guard.reserve(
                VerificationScene.LOGIN,
                "compensate@example.com",
                Duration.ZERO,
                3);
        assertThatThrownBy(() -> guard.reserve(
                VerificationScene.LOGIN,
                "compensate@example.com",
                Duration.ZERO,
                3))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VERIFICATION_DAILY_LIMIT));
    }

    private static ErrorCode reserveAfterBarrier(
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("quota barrier timed out");
        }
        try {
            guard.reserve(
                    VerificationScene.LOGIN,
                    "concurrent@example.com",
                    Duration.ofMinutes(1),
                    10);
            return null;
        } catch (BusinessException exception) {
            return exception.getErrorCode();
        }
    }

    private static void runConcurrentCompensation(
            VerificationSendGuard.Reservation reservation) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                compensateAfterBarrier(reservation, ready, start);
                return null;
            });
            Future<?> second = executor.submit(() -> {
                compensateAfterBarrier(reservation, ready, start);
                return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }
    }

    private static void compensateAfterBarrier(
            VerificationSendGuard.Reservation reservation,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("compensation barrier timed out");
        }
        guard.compensate(reservation);
    }
}
