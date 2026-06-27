package com.chtholly.comment.service;

import com.chtholly.comment.config.CommentProperties;
import com.chtholly.common.exception.BusinessException;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentRateLimiterTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private CommentRateLimiter limiter;

    @BeforeEach
    void setUp() {
        CommentProperties properties = new CommentProperties();
        properties.setRateLimitPerMinute(5);
        limiter = new CommentRateLimiter(redis, properties);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void allowsWithinLimit() {
        when(valueOps.increment("comment:rate:42")).thenReturn(5L);
        limiter.checkAndIncrement(42);
    }

    @Test
    void setsTtlOnFirstRequest() {
        when(valueOps.increment("comment:rate:42")).thenReturn(1L);
        limiter.checkAndIncrement(42);
        verify(redis).expire(eq("comment:rate:42"), eq(Duration.ofSeconds(60)));
    }

    @Test
    void rejectsWhenExceedingLimit() {
        when(valueOps.increment("comment:rate:42")).thenReturn(6L);
        assertThatThrownBy(() -> limiter.checkAndIncrement(42))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
                    assertThat(be.getMessage()).contains("评论过于频繁");
                });
    }
}
