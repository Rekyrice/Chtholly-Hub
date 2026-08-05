package com.chtholly.auth.verification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegacyVerificationQuotaReaderTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;

    private LegacyVerificationQuotaReader reader;

    @BeforeEach
    void setUp() {
        reader = new LegacyVerificationQuotaReader(redis);
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void readsStillLiveIntervalAndDailyStateWithoutWritingLegacyKeys() {
        when(redis.hasKey("auth:code:last:LOGIN:owner@example.com"))
                .thenReturn(true);
        when(values.get(startsWith(
                "auth:code:count:LOGIN:owner@example.com:")))
                .thenReturn("4");

        LegacyVerificationQuotaReader.LegacyQuotaSnapshot snapshot = reader.read(
                VerificationScene.LOGIN,
                "owner@example.com",
                true,
                true);

        assertThat(snapshot.intervalBlocked()).isTrue();
        assertThat(snapshot.dailyCount()).isEqualTo(4L);
    }

    @Test
    void malformedLegacyCountFailsClosed() {
        when(values.get(startsWith(
                "auth:code:count:REGISTER:owner@example.com:")))
                .thenReturn("not-a-number");

        assertThatThrownBy(() -> reader.read(
                VerificationScene.REGISTER,
                "owner@example.com",
                false,
                true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid daily count");
    }
}
