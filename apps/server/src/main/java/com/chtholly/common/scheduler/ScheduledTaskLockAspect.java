package com.chtholly.common.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Adds Redis distributed locks and TTL protection to every {@link Scheduled} method.
 */
@Slf4j
@Aspect
@Component
@Order(0)
public class ScheduledTaskLockAspect {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    private static final Duration MIN_TTL = Duration.ofSeconds(10);
    private static final Duration MAX_DERIVED_TTL = Duration.ofMinutes(30);

    private final DistributedLockService lockService;

    public ScheduledTaskLockAspect(DistributedLockService lockService) {
        this.lockService = lockService;
    }

    /**
     * Wraps scheduled methods with distributed lock acquisition.
     */
    @Around("@annotation(scheduled)")
    public Object aroundScheduled(ProceedingJoinPoint joinPoint, Scheduled scheduled) throws Throwable {
        String taskName = taskName(joinPoint);
        String lockKey = "lock:scheduled:" + taskName;
        Duration ttl = ttlFor(scheduled);
        if (!lockService.tryLock(lockKey, ttl)) {
            log.debug("Another instance is running scheduled task {}, skipping", taskName);
            return null;
        }

        long startedAt = System.nanoTime();
        boolean success = false;
        try {
            Object result = joinPoint.proceed();
            success = true;
            return result;
        } catch (Throwable e) {
            log.error("Scheduled task {} failed", taskName, e);
            return null;
        } finally {
            long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            lockService.recordRun(taskName, durationMs, success);
            lockService.unlock(lockKey);
        }
    }

    private String taskName(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> declaringType = signature.getDeclaringType();
        if (joinPoint.getTarget() != null) {
            declaringType = joinPoint.getTarget().getClass();
        }
        String simpleName = declaringType.getSimpleName();
        int proxyMarker = simpleName.indexOf("$$");
        if (proxyMarker >= 0) {
            simpleName = simpleName.substring(0, proxyMarker);
        }
        return simpleName + "." + signature.getMethod().getName();
    }

    private Duration ttlFor(Scheduled scheduled) {
        long interval = firstPositive(
                scheduled.fixedDelay(),
                scheduled.fixedRate(),
                parseLong(scheduled.fixedDelayString()),
                parseLong(scheduled.fixedRateString()));
        if (interval <= 0) {
            return DEFAULT_TTL;
        }
        Duration derived = Duration.ofMillis(interval).multipliedBy(2);
        if (derived.compareTo(MIN_TTL) < 0) {
            return MIN_TTL;
        }
        if (derived.compareTo(MAX_DERIVED_TTL) > 0) {
            return MAX_DERIVED_TTL;
        }
        return derived;
    }

    private long firstPositive(long... values) {
        for (long value : values) {
            if (value > 0) {
                return value;
            }
        }
        return -1L;
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank() || value.contains("${")) {
            return -1L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
