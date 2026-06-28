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
        when(redisTemplate.hasKey("auth:login:lock:ip:1.1.1.1")).thenReturn(false);
        when(redisTemplate.hasKey("auth:login:lock:alice")).thenReturn(true);

        assertThatThrownBy(() -> guard.assertNotLocked("alice", "1.1.1.1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getHttpStatus()).isEqualTo(HttpStatus.LOCKED.value());
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.LOGIN_LOCKED);
                });
    }

    @Test
    void setsLockAfterFiveIdentifierFailures() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("auth:login:fail:alice")).thenReturn(5L);
        when(valueOps.increment("auth:login:fail:ip:1.1.1.1")).thenReturn(1L);

        guard.onFailure("alice", "1.1.1.1");

        verify(valueOps).set("auth:login:lock:alice", "1", Duration.ofMinutes(15));
    }

    @Test
    void clearsIdentifierCountersOnSuccess() {
        guard.onSuccess("alice");

        verify(redisTemplate).delete("auth:login:fail:alice");
        verify(redisTemplate).delete("auth:login:lock:alice");
    }

    @Test
    void setsIpLockAfterTwentyFailures() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("auth:login:fail:alice")).thenReturn(1L);
        when(valueOps.increment("auth:login:fail:ip:1.1.1.1")).thenReturn(20L);

        guard.onFailure("alice", "1.1.1.1");

        verify(valueOps).set("auth:login:lock:ip:1.1.1.1", "1", Duration.ofMinutes(30));
    }

    @Test
    void doesNotSetIdentifierLockBeforeThreshold() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("auth:login:fail:alice")).thenReturn(4L);
        when(valueOps.increment("auth:login:fail:ip:1.1.1.1")).thenReturn(1L);

        guard.onFailure("alice", "1.1.1.1");

        verify(valueOps, never()).set(eq("auth:login:lock:alice"), any(), any(Duration.class));
    }
}
