package com.chtholly.agent.runtime;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class AgentBoundedCallExecutorTest {

    private final AgentBoundedCallExecutor executor = new AgentBoundedCallExecutor();

    @Test
    void returnsCompletedWorkWithinTheRemainingTurnBudget() {
        AgentTurnBudget budget = AgentTurnBudget.start(Duration.ofSeconds(1), () -> false);

        assertThat(executor.execute(() -> "done", budget, "retrieval")).isEqualTo("done");
    }

    @Test
    void interruptsWorkAndReportsTheStableStageWhenTheBudgetExpires() throws InterruptedException {
        AgentTurnBudget budget = AgentTurnBudget.start(Duration.ofMillis(20), () -> false);
        CountDownLatch interrupted = new CountDownLatch(1);

        assertThatThrownBy(() -> executor.execute(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(5));
                return "late";
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw exception;
            }
        }, budget, "memory_write"))
                .isInstanceOfSatisfying(AgentTurnBudget.UnavailableException.class, unavailable -> {
                    assertThat(unavailable.reason()).isEqualTo(AgentTurnBudget.UnavailableReason.TIMEOUT);
                    assertThat(unavailable.stage()).isEqualTo("memory_write");
                });
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void preservesRuntimeFailuresFromTheBoundedWork() {
        AgentTurnBudget budget = AgentTurnBudget.start(Duration.ofSeconds(1), () -> false);

        assertThatThrownBy(() -> executor.execute(
                () -> {
                    throw new IllegalArgumentException("broken");
                },
                budget,
                "retrieval"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("broken");
    }

    @Test
    void callerInterruptionIsNotMisreportedAsATurnTimeout() {
        AgentTurnBudget budget = AgentTurnBudget.start(Duration.ofSeconds(1), () -> false);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        try {
            Thread.currentThread().interrupt();
            Throwable failure = catchThrowable(() -> executor.execute(() -> {
                releaseWorker.await();
                return "late";
            }, budget, "retrieval"));

            assertThat(failure)
                    .isNotNull()
                    .isNotInstanceOf(AgentTurnBudget.UnavailableException.class)
                    .hasMessageContaining("retrieval");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            releaseWorker.countDown();
            Thread.interrupted();
        }
    }

    @Test
    void propagatesTheCallingMdcContextWithoutLeakingWorkerChanges() {
        AgentTurnBudget budget = AgentTurnBudget.start(Duration.ofSeconds(1), () -> false);
        MDC.put("correlationId", "turn-correlation");
        try {
            String workerValue = executor.execute(() -> {
                String inherited = MDC.get("correlationId");
                MDC.put("correlationId", "worker-only");
                return inherited;
            }, budget, "retrieval");

            assertThat(workerValue).isEqualTo("turn-correlation");
            assertThat(MDC.get("correlationId")).isEqualTo("turn-correlation");
        } finally {
            MDC.clear();
        }
    }
}
