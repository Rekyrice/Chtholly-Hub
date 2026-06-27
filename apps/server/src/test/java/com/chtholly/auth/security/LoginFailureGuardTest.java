package com.chtholly.auth.security;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginFailureGuardTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private LoginFailureGuard guard;

    @BeforeEach
    void setUp() {
        guard = new LoginFailureGuard(redisTemplate);
    }

    @Test
    void throws423WhenIdentifierLocked() {
        when(redisTemplate.hasKey("login:lock:ip:1.1.1.1")).thenReturn(false);
        when(redisTemplate.hasKey("login:lock:13800000000")).thenReturn(true);

        assertThatThrownBy(() -> guard.assertNotLocked("13800000000", "1.1.1.1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getHttpStatus()).isEqualTo(HttpStatus.LOCKED.value());
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_LOCKED);
                });
    }

    @Test
    void setsLockAfterFiveIdentifierFailures() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("login:fail:13800000000")).thenReturn(5L);
        when(valueOps.increment("login:fail:ip:1.1.1.1")).thenReturn(1L);

        guard.onFailure("13800000000", "1.1.1.1");

        verify(valueOps).set("login:lock:13800000000", "1", Duration.ofSeconds(900));
    }

    @Test
    void clearsIdentifierCountersOnSuccess() {
        guard.onSuccess("13800000000");

        verify(redisTemplate).delete("login:fail:13800000000");
        verify(redisTemplate).delete("login:lock:13800000000");
    }

    @Test
    void setsIpLockAfterTwentyFailures() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("login:fail:13800000000")).thenReturn(1L);
        when(valueOps.increment("login:fail:ip:1.1.1.1")).thenReturn(20L);

        guard.onFailure("13800000000", "1.1.1.1");

        verify(valueOps).set("login:lock:ip:1.1.1.1", "1", Duration.ofMinutes(30));
    }

    @Test
    void doesNotSetIdentifierLockBeforeThreshold() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("login:fail:13800000000")).thenReturn(4L);
        when(valueOps.increment("login:fail:ip:1.1.1.1")).thenReturn(1L);

        guard.onFailure("13800000000", "1.1.1.1");

        verify(valueOps, never()).set(eq("login:lock:13800000000"), any(), any(Duration.class));
    }
}
