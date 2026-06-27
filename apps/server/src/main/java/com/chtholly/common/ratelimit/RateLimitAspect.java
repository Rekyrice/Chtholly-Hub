package com.chtholly.common.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** AOP interceptor for {@link RateLimit} on controller methods. */
@Slf4j
@Aspect
@Component
@Order(0)
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RateLimiter rateLimiter;
    private final RateLimitContextResolver contextResolver;

    @Around("@annotation(com.chtholly.common.ratelimit.RateLimit) || @annotation(com.chtholly.common.ratelimit.RateLimits)")
    public Object enforce(ProceedingJoinPoint joinPoint) throws Throwable {
        List<RateLimit> limits = contextResolver.resolveLimits(joinPoint);
        for (RateLimit limit : limits) {
            String identifier = contextResolver.resolveIdentifier(limit, joinPoint);
            String redisKey = RateLimitKeys.build(limit.dimension(), identifier, limit.key(), limit.windowSeconds());
            RateLimitResult result = rateLimiter.tryAcquire(redisKey, limit.maxRequests(), limit.windowSeconds());
            if (!result.permitted()) {
                log.debug("Rate limit exceeded key={} identifier={}", limit.key(), identifier);
                throw new RateLimitException(result.retryAfterSeconds());
            }
        }
        return joinPoint.proceed();
    }
}
