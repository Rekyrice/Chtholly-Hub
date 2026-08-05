package com.chtholly.integration;

import com.chtholly.auth.token.RedisRefreshTokenStore;
import com.chtholly.auth.token.RefreshSessionEpochAuthority;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies Redis membership scripts with a controlled MySQL authority double. */
@Testcontainers(disabledWithoutDocker = true)
class RedisRefreshTokenStoreIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisRefreshTokenStore store;
    private static RefreshSessionEpochAuthority epochAuthority;
    private static AtomicLong epoch;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(
                        REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        epoch = new AtomicLong(1L);
        epochAuthority = mock(RefreshSessionEpochAuthority.class);
        when(epochAuthority.current(7L)).thenAnswer(ignored -> epoch.get());
        doAnswer(ignored -> {
            epoch.incrementAndGet();
            return null;
        }).when(epochAuthority).advance(7L);
        store = new RedisRefreshTokenStore(redis, epochAuthority);
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void clearRedis() {
        epoch.set(1L);
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    @Test
    void revokeAfterRotationInvalidatesTheReplacementSession() {
        store.storeToken(7L, "old", Duration.ofMinutes(5));
        assertThat(store.rotateToken(
                7L, "old", "replacement", Duration.ofMinutes(5))).isTrue();

        store.revokeAll(7L);

        assertThat(store.isTokenValid(7L, "replacement")).isFalse();
    }

    @Test
    void rotationAfterRevokeCannotResurrectTheOldSession() {
        store.storeToken(7L, "old", Duration.ofMinutes(5));

        store.revokeAll(7L);

        assertThat(store.rotateToken(
                7L, "old", "replacement", Duration.ofMinutes(5))).isFalse();
        assertThat(store.isTokenValid(7L, "old")).isFalse();
        assertThat(store.isTokenValid(7L, "replacement")).isFalse();
    }

    @Test
    void bareTaggedAndLegacyValuesAreRejectedWithoutMigration() {
        String legacyKey = "auth:rt:7:legacy";
        String currentKey = "auth:rt:{7}:legacy";
        redis.opsForValue().set(legacyKey, "1", Duration.ofMinutes(5));
        redis.opsForValue().set(currentKey, "1", Duration.ofMinutes(5));

        assertThat(store.isTokenValid(7L, "legacy")).isFalse();

        assertThat(redis.hasKey(legacyKey)).isTrue();
        assertThat(redis.opsForValue().get(currentKey)).isEqualTo("1");
    }

    @Test
    void revokeTokenDeletesLegacyAndCurrentMembership() {
        String legacyKey = "auth:rt:7:shared-jti";
        String currentKey = "auth:rt:{7}:shared-jti";
        redis.opsForValue().set(legacyKey, "1", Duration.ofMinutes(5));
        store.storeToken(7L, "shared-jti", Duration.ofMinutes(5));

        store.revokeToken(7L, "shared-jti");

        assertThat(redis.hasKey(legacyKey)).isFalse();
        assertThat(redis.hasKey(currentKey)).isFalse();
        assertThat(store.isTokenValid(7L, "shared-jti")).isFalse();
    }

    @Test
    void legacyValueCannotMigrateAfterUserWideRevocation() {
        String legacyKey = "auth:rt:7:legacy";
        String currentKey = "auth:rt:{7}:legacy";
        redis.opsForValue().set(legacyKey, "1", Duration.ofMinutes(5));

        store.revokeAll(7L);

        assertThat(store.isTokenValid(7L, "legacy")).isFalse();
        assertThat(redis.hasKey(currentKey)).isFalse();
    }

    @Test
    void capturedEpochCannotStoreAfterUserWideRevocation() {
        long capturedEpoch = store.captureEpoch(7L);

        store.revokeAll(7L);

        assertThat(store.storeTokenIfEpochMatches(
                7L,
                "stale-login",
                Duration.ofMinutes(5),
                capturedEpoch)).isFalse();
        assertThat(store.isTokenValid(7L, "stale-login")).isFalse();
    }

    @Test
    void capturedEpochStoresWhileTheAuthorityEpochIsUnchanged() {
        long capturedEpoch = store.captureEpoch(7L);

        assertThat(store.storeTokenIfEpochMatches(
                7L,
                "current-login",
                Duration.ofMinutes(5),
                capturedEpoch)).isTrue();
        assertThat(redis.opsForValue().get("auth:rt:{7}:current-login"))
                .isEqualTo("mysql:1");
        assertThat(store.isTokenValid(7L, "current-login")).isTrue();
    }

    @Test
    void concurrentRotationHasExactlyOneWinner() throws Exception {
        store.storeToken(7L, "old", Duration.ofMinutes(5));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() ->
                    rotateAfterBarrier("replacement-a", ready, start));
            Future<Boolean> second = executor.submit(() ->
                    rotateAfterBarrier("replacement-b", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(store.isTokenValid(7L, "old")).isFalse();
            assertThat(store.isTokenValid(7L, "replacement-a")
                    ^ store.isTokenValid(7L, "replacement-b")).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean rotateAfterBarrier(
            String replacement,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("rotation barrier timed out");
        }
        return store.rotateToken(
                7L, "old", replacement, Duration.ofMinutes(5));
    }
}
