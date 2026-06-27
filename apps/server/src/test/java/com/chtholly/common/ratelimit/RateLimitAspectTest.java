package com.chtholly.common.ratelimit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitAspectTest {

    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private RateLimitContextResolver contextResolver;
    @Mock
    private ProceedingJoinPoint joinPoint;

    private RateLimitAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new RateLimitAspect(rateLimiter, contextResolver);
    }

    @Test
    void throwsRateLimitExceptionWhenRejected() throws Throwable {
        RateLimit limit = DummyController.class.getDeclaredMethod("limited").getAnnotation(RateLimit.class);
        when(contextResolver.resolveLimits(joinPoint)).thenReturn(List.of(limit));
        when(contextResolver.resolveIdentifier(limit, joinPoint)).thenReturn("10.0.0.1");
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.deny(45));

        assertThatThrownBy(() -> aspect.enforce(joinPoint))
                .isInstanceOf(RateLimitException.class)
                .extracting(ex -> ((RateLimitException) ex).getRetryAfterSeconds())
                .isEqualTo(45);

        verify(joinPoint, never()).proceed();
    }

    @Test
    void proceedsWhenAllLimitsPass() throws Throwable {
        RateLimit limit = DummyController.class.getDeclaredMethod("limited").getAnnotation(RateLimit.class);
        when(contextResolver.resolveLimits(joinPoint)).thenReturn(List.of(limit));
        when(contextResolver.resolveIdentifier(limit, joinPoint)).thenReturn("10.0.0.1");
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(RateLimitResult.permit());
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.enforce(joinPoint);

        assertThat(result).isEqualTo("ok");
        verify(joinPoint).proceed();
    }

    static class DummyController {
        @RateLimit(key = "test", maxRequests = 5, windowSeconds = 60, dimension = RateLimitDimension.IP)
        void limited() {
        }
    }
}
