package com.chtholly.auth.verification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisVerificationCodeStoreTest {

    @Mock
    private StringRedisTemplate redis;

    private RedisVerificationCodeStore store;

    @BeforeEach
    void setUp() {
        store = new RedisVerificationCodeStore(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void savesAllFieldsAndTtlAtomicallyWithoutLeakingTheIdentifier() {
        when(redis.execute(
                any(DefaultRedisScript.class),
                any(List.class),
                eq("123456"),
                eq("generation-1"),
                eq("5"),
                eq("300000"),
                eq("86400000")))
                .thenReturn(1L);

        store.saveCode(
                VerificationScene.LOGIN.name(),
                "Owner+private@example.com",
                new VerificationCodeStore.IssuedCode("123456", "generation-1"),
                Duration.ofMinutes(5),
                5);

        ArgumentCaptor<DefaultRedisScript<Long>> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(
                script.capture(),
                keys.capture(),
                eq("123456"),
                eq("generation-1"),
                eq("5"),
                eq("300000"),
                eq("86400000"));
        assertThat(keys.getValue()).hasSize(2).allSatisfy(key -> {
            assertThat(key).startsWith("auth:code:{");
            assertThat(key).doesNotContain("Owner", "private", "example.com");
        });
        assertThat(keys.getValue().get(0)).endsWith("}");
        assertThat(keys.getValue().get(1)).endsWith(":legacy-disabled");
        assertThat(script.getValue().getScriptAsString())
                .contains("redis.call('HSET', KEYS[1]")
                .contains("'version', ARGV[2]")
                .contains("redis.call('PEXPIRE', KEYS[1], ARGV[4])");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsAnIndeterminateSaveResult() {
        when(redis.execute(
                any(DefaultRedisScript.class),
                any(List.class),
                any(), any(), any(), any(), any()))
                .thenReturn(null);

        assertThatThrownBy(() -> store.saveCode(
                VerificationScene.REGISTER.name(),
                "owner@example.com",
                new VerificationCodeStore.IssuedCode("123456", "generation-1"),
                Duration.ofMinutes(5),
                5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persistence");
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifiesAndConsumesThroughOneAtomicScript() {
        when(redis.execute(
                any(DefaultRedisScript.class),
                any(List.class),
                eq("123456"),
                eq("1800000")))
                .thenReturn("SUCCESS|1|5");

        VerificationCheckResult result = store.verify(
                VerificationScene.LOGIN.name(),
                "owner@example.com",
                "123456");

        assertThat(result.status()).isEqualTo(VerificationCodeStatus.SUCCESS);
        assertThat(result.attempts()).isEqualTo(1);
        ArgumentCaptor<DefaultRedisScript<String>> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis).execute(
                script.capture(),
                any(List.class),
                eq("123456"),
                eq("1800000"));
        assertThat(script.getValue().getScriptAsString())
                .contains("redis.call('HINCRBY'")
                .contains("redis.call('DEL', KEYS[1])")
                .contains("TOO_MANY_ATTEMPTS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToTheLegacyKeyOnlyWhenTheCurrentKeyIsMissing() {
        when(redis.execute(
                any(DefaultRedisScript.class),
                any(List.class),
                eq("123456"),
                eq("1800000")))
                .thenReturn("NOT_FOUND|0|0", "SUCCESS|0|5");

        VerificationCheckResult result = store.verify(
                VerificationScene.LOGIN.name(),
                "owner@example.com",
                "123456");

        assertThat(result.status()).isEqualTo(VerificationCodeStatus.SUCCESS);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis, org.mockito.Mockito.times(2)).execute(
                any(DefaultRedisScript.class),
                keys.capture(),
                eq("123456"),
                eq("1800000"));
        assertThat(keys.getAllValues().get(0).get(0).toString())
                .startsWith("auth:code:{")
                .doesNotContain("owner@example.com");
        assertThat(keys.getAllValues().get(0)).hasSize(2);
        assertThat(keys.getAllValues().get(1))
                .containsExactly(
                        "auth:code:LOGIN:owner@example.com",
                        "auth:code:LOGIN:owner@example.com");
    }

    @Test
    @SuppressWarnings("unchecked")
    void currentFenceBlocksLegacyFallbackAfterTheNewCodeWasConsumed() {
        when(redis.execute(
                any(DefaultRedisScript.class),
                any(List.class),
                eq("old-code"),
                eq("1800000")))
                .thenReturn("LEGACY_BLOCKED|0|0");

        VerificationCheckResult result = store.verify(
                VerificationScene.RESET_PASSWORD.name(),
                "owner@example.com",
                "old-code");

        assertThat(result.status()).isEqualTo(VerificationCodeStatus.NOT_FOUND);
        verify(redis).execute(
                any(DefaultRedisScript.class),
                any(List.class),
                eq("old-code"),
                eq("1800000"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void conditionalInvalidationChecksWriteOwnership() {
        when(redis.execute(
                any(DefaultRedisScript.class),
                any(List.class),
                eq("generation-1")))
                .thenReturn(0L);

        assertThat(store.invalidateIfCurrent(
                VerificationScene.RESET_PASSWORD.name(),
                "owner@example.com",
                "generation-1"))
                .isFalse();

        ArgumentCaptor<DefaultRedisScript<Long>> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis).execute(
                script.capture(),
                any(List.class),
                eq("generation-1"));
        assertThat(script.getValue().getScriptAsString())
                .contains("HGET', KEYS[1], 'version'")
                .contains("redis.call('DEL', KEYS[1])");
    }

    @Test
    void issuedCodeDoesNotExposeTheSecretThroughToString() {
        VerificationCodeStore.IssuedCode issuedCode =
                new VerificationCodeStore.IssuedCode("123456", "generation-1");

        assertThat(issuedCode.toString())
                .doesNotContain("123456")
                .contains("<redacted>", "generation-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedVerificationResultFailsClosed() {
        when(redis.execute(
                any(DefaultRedisScript.class),
                any(List.class),
                eq("123456"),
                eq("1800000")))
                .thenReturn("SUCCESS|not-a-number|5");

        assertThatThrownBy(() -> store.verify(
                VerificationScene.LOGIN.name(),
                "owner@example.com",
                "123456"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid result");
    }

    @Test
    @SuppressWarnings("unchecked")
    void impossibleVerificationCountersFailClosed() {
        when(redis.execute(
                any(DefaultRedisScript.class),
                any(List.class),
                eq("123456"),
                eq("1800000")))
                .thenReturn("SUCCESS|0|0");

        assertThatThrownBy(() -> store.verify(
                VerificationScene.LOGIN.name(),
                "owner@example.com",
                "123456"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid result");
    }
}
