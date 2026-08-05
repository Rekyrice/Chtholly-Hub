package com.chtholly.llm.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedissonRagPostMutationLockTest {

    @Mock private RedissonClient redisson;
    @Mock private RLock lock;

    private RedissonRagPostMutationLock mutationLock;

    @BeforeEach
    void setUp() {
        mutationLock = new RedissonRagPostMutationLock(redisson);
        when(redisson.getLock("lock:rag:post:42")).thenReturn(lock);
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void executesUnderWatchdogLockAndReleasesOwnership() throws Exception {
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);

        assertThat(mutationLock.withLock(42L, () -> 7)).isEqualTo(7);

        verify(lock).unlock();
        verify(lock, never()).isHeldByCurrentThread();
    }

    @Test
    void timeoutFailsWithoutExecutingMutation() throws Exception {
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(false);
        AtomicBoolean executed = new AtomicBoolean();

        assertThatThrownBy(() -> mutationLock.withLock(42L, () -> {
                    executed.set(true);
                    return 0;
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timed out");

        assertThat(executed).isFalse();
        verify(lock, never()).unlock();
    }

    @Test
    void interruptionRestoresFlagAndDoesNotRunMutation() throws Exception {
        when(lock.tryLock(5L, TimeUnit.SECONDS))
                .thenThrow(new InterruptedException("stop"));
        AtomicBoolean executed = new AtomicBoolean();

        assertThatThrownBy(() -> mutationLock.withLock(42L, () -> {
                    executed.set(true);
                    return 0;
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Interrupted");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(executed).isFalse();
        verify(lock, never()).unlock();
    }

    @Test
    void mutationFailureKeepsUnlockFailureAsSuppressed() throws Exception {
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        IllegalArgumentException mutationFailure =
                new IllegalArgumentException("mutation failed");
        IllegalStateException unlockFailure =
                new IllegalStateException("unlock failed");
        org.mockito.Mockito.doThrow(unlockFailure).when(lock).unlock();

        assertThatThrownBy(() -> mutationLock.withLock(42L, () -> {
                    throw mutationFailure;
                }))
                .isSameAs(mutationFailure)
                .satisfies(failure -> {
                    assertThat(failure.getSuppressed()).hasSize(1);
                    assertThat(failure.getSuppressed()[0])
                            .isInstanceOf(IllegalStateException.class)
                            .hasCause(unlockFailure);
                });
    }

    @Test
    void lostOwnershipFailsTheMutationOutcome() throws Exception {
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalMonitorStateException("lost"))
                .when(lock).unlock();

        assertThatThrownBy(() -> mutationLock.withLock(42L, () -> 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("release")
                .hasCauseInstanceOf(IllegalMonitorStateException.class);

        verify(lock).unlock();
    }
}
