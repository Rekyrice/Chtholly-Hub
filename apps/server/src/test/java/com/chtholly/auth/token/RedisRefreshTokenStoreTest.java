package com.chtholly.auth.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisRefreshTokenStoreTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RefreshSessionEpochAuthority epochAuthority;

    private RedisRefreshTokenStore store;

    @BeforeEach
    void setUp() {
        store = new RedisRefreshTokenStore(redisTemplate, epochAuthority);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void pendingBootstrapRequiresAnActiveSynchronizedTransaction() {
        assertThatThrownBy(() -> store.storeInitialTokenForPendingUser(
                7L, "new-jti", Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");

        verifyNoInteractions(epochAuthority, redisTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingBootstrapStoresEpochOneOnlyForAnUncommittedInitialUser() {
        beginTransaction();
        when(epochAuthority.hasInitialEpochInCurrentTransaction(7L))
                .thenReturn(true);
        when(epochAuthority.existsInCommittedSnapshot(7L)).thenReturn(false);
        List<String> keys = List.of(
                "auth:rt:{7}:new-jti",
                "auth:rt:{7}:revoked:new-jti");
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(keys),
                eq("30000"),
                eq("mysql:1")))
                .thenReturn(1L);

        store.storeInitialTokenForPendingUser(
                7L, "new-jti", Duration.ofSeconds(30));

        verify(epochAuthority).hasInitialEpochInCurrentTransaction(7L);
        verify(epochAuthority).existsInCommittedSnapshot(7L);
        verify(epochAuthority, never()).current(7L);
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(keys),
                eq("30000"),
                eq("mysql:1"));
    }

    @Test
    void pendingBootstrapRejectsAnAlreadyCommittedUser() {
        beginTransaction();
        when(epochAuthority.hasInitialEpochInCurrentTransaction(7L))
                .thenReturn(true);
        when(epochAuthority.existsInCommittedSnapshot(7L)).thenReturn(true);

        assertThatThrownBy(() -> store.storeInitialTokenForPendingUser(
                7L, "new-jti", Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending user");

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rollbackDiscardDeletesOnlyAnInitialMembershipForAnAbsentUser() {
        when(epochAuthority.existsInCommittedSnapshot(7L)).thenReturn(false);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of("auth:rt:{7}:new-jti")),
                eq("mysql:1")))
                .thenReturn(1L);

        store.discardInitialTokenForPendingUser(7L, "new-jti");

        ArgumentCaptor<DefaultRedisScript<Long>> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redisTemplate).execute(
                script.capture(),
                eq(List.of("auth:rt:{7}:new-jti")),
                eq("mysql:1"));
        assertThat(script.getValue().getScriptAsString())
                .contains("redis.call('GET', KEYS[1]) == ARGV[1]")
                .contains("redis.call('DEL', KEYS[1])");
    }

    @Test
    void rollbackDiscardCannotDeleteMembershipForACommittedUser() {
        when(epochAuthority.existsInCommittedSnapshot(7L)).thenReturn(true);

        store.discardInitialTokenForPendingUser(7L, "new-jti");

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void captureEpochReadsOnlyTheMysqlAuthority() {
        when(epochAuthority.current(7L)).thenReturn(4L);

        assertThat(store.captureEpoch(7L)).isEqualTo(4L);

        verify(epochAuthority).current(7L);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void conditionalStoreRejectsAStaleExpectedEpochWithoutWritingRedis() {
        when(epochAuthority.current(7L)).thenReturn(5L);

        assertThat(store.storeTokenIfEpochMatches(
                7L, "new-jti", Duration.ofSeconds(30), 4L)).isFalse();

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void storeTokenDoesNotRetryAcrossAnEpochChange() {
        when(epochAuthority.current(7L)).thenReturn(4L, 5L);

        assertThatThrownBy(() -> store.storeToken(
                7L, "new-jti", Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class);

        verify(epochAuthority, times(2)).current(7L);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void conditionalStoreWritesATaggedMembershipAfterTheFirstAuthorityRead() {
        when(epochAuthority.current(7L)).thenReturn(4L, 4L);
        List<String> keys = List.of(
                "auth:rt:{7}:new-jti",
                "auth:rt:{7}:revoked:new-jti");
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(keys),
                eq("30000"),
                eq("mysql:4")))
                .thenReturn(1L);

        assertThat(store.storeTokenIfEpochMatches(
                7L, "new-jti", Duration.ofSeconds(30), 4L)).isTrue();

        verify(epochAuthority, times(2)).current(7L);
        ArgumentCaptor<DefaultRedisScript<Long>> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redisTemplate).execute(
                script.capture(),
                eq(keys),
                eq("30000"),
                eq("mysql:4"));
        assertThat(script.getValue().getScriptAsString())
                .contains("redis.call('PSETEX', KEYS[1], ARGV[1], ARGV[2])")
                .doesNotContain("epoch", "INCR");
    }

    @Test
    @SuppressWarnings("unchecked")
    void conditionalStoreCompensatesWhenTheSecondAuthorityReadChanges() {
        when(epochAuthority.current(7L)).thenReturn(4L, 5L);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        "auth:rt:{7}:new-jti",
                        "auth:rt:{7}:revoked:new-jti")),
                eq("30000"),
                eq("mysql:4")))
                .thenReturn(1L);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of("auth:rt:{7}:new-jti")),
                eq("mysql:4")))
                .thenReturn(1L);

        assertThat(store.storeTokenIfEpochMatches(
                7L, "new-jti", Duration.ofSeconds(30), 4L)).isFalse();

        ArgumentCaptor<DefaultRedisScript<Long>> compensation =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redisTemplate).execute(
                compensation.capture(),
                eq(List.of("auth:rt:{7}:new-jti")),
                eq("mysql:4"));
        assertThat(compensation.getValue().getScriptAsString())
                .contains("redis.call('GET', KEYS[1]) == ARGV[1]")
                .contains("redis.call('DEL', KEYS[1])");
    }

    @Test
    @SuppressWarnings("unchecked")
    void validationRequiresAnExactMysqlTaggedMembershipAndTwoStableReads() {
        when(epochAuthority.current(7L)).thenReturn(4L, 4L);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of("auth:rt:{7}:jti")),
                eq("mysql:4")))
                .thenReturn(1L);

        assertThat(store.isTokenValid(7L, "jti")).isTrue();

        verify(epochAuthority, times(2)).current(7L);
        ArgumentCaptor<DefaultRedisScript<Long>> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redisTemplate).execute(
                script.capture(),
                eq(List.of("auth:rt:{7}:jti")),
                eq("mysql:4"));
        assertThat(script.getValue().getScriptAsString())
                .contains("redis.call('GET', KEYS[1]) == ARGV[1]")
                .doesNotContain("auth:rt:", "PSETEX");
    }

    @Test
    @SuppressWarnings("unchecked")
    void validationRejectsBareTaggedAndLegacyValuesWithoutMigration() {
        when(epochAuthority.current(7L)).thenReturn(4L);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of("auth:rt:{7}:legacy-jti")),
                eq("mysql:4")))
                .thenReturn(0L);

        assertThat(store.isTokenValid(7L, "legacy-jti")).isFalse();

        verify(redisTemplate, never()).opsForValue();
        verify(redisTemplate, never()).delete("auth:rt:7:legacy-jti");
    }

    @Test
    @SuppressWarnings("unchecked")
    void validationFailsWhenTheSecondAuthorityReadChanges() {
        when(epochAuthority.current(7L)).thenReturn(4L, 5L);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of("auth:rt:{7}:jti")),
                eq("mysql:4")))
                .thenReturn(1L);

        assertThat(store.isTokenValid(7L, "jti")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rotationCompensatesTheReplacementWhenTheSecondReadChanges() {
        when(epochAuthority.current(7L)).thenReturn(4L, 5L);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        "auth:rt:{7}:old-jti",
                        "auth:rt:{7}:new-jti",
                        "auth:rt:{7}:revoked:new-jti")),
                eq("30000"),
                eq("mysql:4")))
                .thenReturn(1L);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of("auth:rt:{7}:new-jti")),
                eq("mysql:4")))
                .thenReturn(1L);

        assertThat(store.rotateToken(
                7L, "old-jti", "new-jti", Duration.ofSeconds(30)))
                .isFalse();

        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("auth:rt:{7}:new-jti")),
                eq("mysql:4"));
    }

    @Test
    void rotationRejectsTheSameJtiBeforeAccessingEitherAuthority() {
        assertThat(store.rotateToken(
                7L, "same", "same", Duration.ofSeconds(30))).isFalse();

        verifyNoInteractions(epochAuthority, redisTemplate);
    }

    @Test
    void revokeAllAdvancesMysqlExactlyOnceWithoutAccessingRedis() {
        store.revokeAll(7L);

        verify(epochAuthority).advance(7L);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void revokeTokenDeletesTaggedAndLegacyMembershipAndKeepsTheFence() {
        when(redisTemplate.getExpire(
                "auth:rt:7:jti", TimeUnit.MILLISECONDS))
                .thenReturn(120_000L);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        "auth:rt:{7}:jti",
                        "auth:rt:{7}:revoked:jti")),
                eq("120000"),
                eq("30000")))
                .thenReturn(1L);

        store.revokeToken(7L, "jti");

        verify(redisTemplate).delete("auth:rt:7:jti");
    }

    @Test
    void zeroAndSubMillisecondTtlAreRejected() {
        assertThatThrownBy(() -> store.storeTokenIfEpochMatches(
                7L, "jti", Duration.ZERO, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.rotateToken(
                7L, "old", "new", Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
}
