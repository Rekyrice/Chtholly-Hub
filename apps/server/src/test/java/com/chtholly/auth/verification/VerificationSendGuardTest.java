package com.chtholly.auth.verification;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationSendGuardTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private LegacyVerificationQuotaReader legacyQuotaReader;

    private VerificationSendGuard guard;

    @BeforeEach
    void setUp() {
        guard = new VerificationSendGuard(redis, legacyQuotaReader);
        org.mockito.Mockito.lenient().when(legacyQuotaReader.read(
                any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new LegacyVerificationQuotaReader.LegacyQuotaSnapshot(
                        false, 0L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reservesIntervalAndDailyQuotaAtomicallyWithoutLeakingIdentifier() {
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(), any(), any(), any(), any()))
                .thenReturn(0L);

        guard.reserve(
                VerificationScene.LOGIN,
                "Owner+private@example.com",
                Duration.ofSeconds(60),
                10);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<DefaultRedisScript<Long>> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis).execute(
                script.capture(),
                keys.capture(),
                any(), eq("60000"), eq("10"), any(), eq("0"));

        assertThat(keys.getValue())
                .hasSize(2)
                .allSatisfy(key -> {
                    assertThat(key).startsWith("auth:code:quota:{");
                    assertThat(key).doesNotContain("Owner", "private", "example.com");
                });
        assertThat(hashTag(keys.getValue().get(0)))
                .isEqualTo(hashTag(keys.getValue().get(1)));
        assertThat(script.getValue().getScriptAsString())
                .contains("redis.call('PSETEX', KEYS[1]")
                .contains("redis.call('INCR', KEYS[2]");
    }

    @Test
    void intervalRejectionUsesStableBusinessError() {
        stubReservationResult(1L);

        assertThatThrownBy(() -> guard.reserve(
                VerificationScene.REGISTER,
                "owner@example.com",
                Duration.ofMinutes(1),
                10))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VERIFICATION_RATE_LIMIT));
    }

    @Test
    void dailyRejectionUsesStableBusinessError() {
        stubReservationResult(2L);

        assertThatThrownBy(() -> guard.reserve(
                VerificationScene.RESET_PASSWORD,
                "owner@example.com",
                Duration.ofMinutes(1),
                10))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VERIFICATION_DAILY_LIMIT));
    }

    @Test
    void liveLegacyIntervalRejectsBeforeWritingTheNewNamespace() {
        when(legacyQuotaReader.read(
                eq(VerificationScene.LOGIN),
                eq("owner@example.com"),
                eq(true),
                eq(true)))
                .thenReturn(new LegacyVerificationQuotaReader.LegacyQuotaSnapshot(
                        true, 3L));

        assertThatThrownBy(() -> guard.reserve(
                VerificationScene.LOGIN,
                "owner@example.com",
                Duration.ofMinutes(1),
                10))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VERIFICATION_RATE_LIMIT));

        verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void seedsTheNewDailyCounterFromTheLegacyCount() {
        when(legacyQuotaReader.read(
                eq(VerificationScene.LOGIN),
                eq("owner@example.com"),
                eq(false),
                eq(true)))
                .thenReturn(new LegacyVerificationQuotaReader.LegacyQuotaSnapshot(
                        false, 7L));
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(), eq("0"), eq("10"), any(), eq("7")))
                .thenReturn(0L);

        guard.reserve(
                VerificationScene.LOGIN,
                "owner@example.com",
                Duration.ZERO,
                10);

        ArgumentCaptor<DefaultRedisScript<Long>> script =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis).execute(
                script.capture(),
                anyList(),
                any(), eq("0"), eq("10"), any(), eq("7"));
        assertThat(script.getValue().getScriptAsString())
                .contains("legacy_daily + 1");
    }

    @Test
    void missingRedisResultFailsClosed() {
        stubReservationResult(null);

        assertThatThrownBy(() -> guard.reserve(
                VerificationScene.LOGIN,
                "owner@example.com",
                Duration.ofMinutes(1),
                10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservation");
    }

    @Test
    @SuppressWarnings("unchecked")
    void compensationIsSingleFlightAndChecksIntervalOwnership() {
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(), any(), any(), any(), any()))
                .thenReturn(0L);
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(), any(), any()))
                .thenReturn(1L);
        VerificationSendGuard.Reservation reservation = guard.reserve(
                VerificationScene.LOGIN,
                "owner@example.com",
                Duration.ofMinutes(1),
                10);

        guard.compensate(reservation);
        guard.compensate(reservation);

        ArgumentCaptor<DefaultRedisScript<Long>> scripts =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis).execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(), any(), any(), any(), any());
        verify(redis).execute(
                scripts.capture(),
                anyList(),
                any(), any(), any());
        verifyNoMoreInteractions(redis);
        assertThat(scripts.getValue().getScriptAsString())
                .contains("redis.call('GET', KEYS[1]) == ARGV[1]")
                .contains("redis.call('DECR', KEYS[2])");
    }

    @SuppressWarnings("unchecked")
    private void stubReservationResult(Long result) {
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(), any(), any(), any(), any()))
                .thenReturn(result);
    }

    private static String hashTag(String key) {
        return key.substring(key.indexOf('{') + 1, key.indexOf('}'));
    }
}
