package com.chtholly.common.scheduler;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledTaskLockAspectTest {

    @Test
    void skipsScheduledMethodWhenLockIsHeldByAnotherInstance() throws Throwable {
        DistributedLockService lockService = mock(DistributedLockService.class);
        ScheduledTaskLockAspect aspect = new ScheduledTaskLockAspect(lockService);
        ProceedingJoinPoint joinPoint = joinPointFor("slowJob");
        Scheduled scheduled = scheduledAnnotation("slowJob");
        when(lockService.tryLock(eq("lock:scheduled:TestJobs.slowJob"), eq(Duration.ofMinutes(30)))).thenReturn(false);

        aspect.aroundScheduled(joinPoint, scheduled);

        verify(joinPoint, never()).proceed();
        verify(lockService, never()).unlock("lock:scheduled:TestJobs.slowJob");
    }

    @Test
    void unlocksAfterScheduledMethodCompletes() throws Throwable {
        DistributedLockService lockService = mock(DistributedLockService.class);
        ScheduledTaskLockAspect aspect = new ScheduledTaskLockAspect(lockService);
        ProceedingJoinPoint joinPoint = joinPointFor("slowJob");
        Scheduled scheduled = scheduledAnnotation("slowJob");
        when(lockService.tryLock(eq("lock:scheduled:TestJobs.slowJob"), eq(Duration.ofMinutes(30)))).thenReturn(true);

        aspect.aroundScheduled(joinPoint, scheduled);

        verify(joinPoint).proceed();
        verify(lockService).recordRun(eq("TestJobs.slowJob"), org.mockito.ArgumentMatchers.anyLong(), eq(true));
        verify(lockService).unlock("lock:scheduled:TestJobs.slowJob");
    }

    @Test
    void derivesShortTtlFromFastFixedDelayJobs() throws Throwable {
        DistributedLockService lockService = mock(DistributedLockService.class);
        ScheduledTaskLockAspect aspect = new ScheduledTaskLockAspect(lockService);
        ProceedingJoinPoint joinPoint = joinPointFor("fastJob");
        Scheduled scheduled = scheduledAnnotation("fastJob");
        when(lockService.tryLock(eq("lock:scheduled:TestJobs.fastJob"), eq(Duration.ofSeconds(10)))).thenReturn(false);

        aspect.aroundScheduled(joinPoint, scheduled);

        verify(joinPoint, never()).proceed();
    }

    private ProceedingJoinPoint joinPointFor(String methodName) throws NoSuchMethodException {
        Method method = TestJobs.class.getDeclaredMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringType()).thenReturn(TestJobs.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(new TestJobs());
        return joinPoint;
    }

    private Scheduled scheduledAnnotation(String methodName) throws NoSuchMethodException {
        return TestJobs.class.getDeclaredMethod(methodName).getAnnotation(Scheduled.class);
    }

    static class TestJobs {
        @Scheduled(cron = "0 */30 * * * *")
        void slowJob() {
        }

        @Scheduled(fixedDelay = 1000L)
        void fastJob() {
        }
    }
}
