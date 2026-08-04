package com.chtholly.agent.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs blocking agent stages on virtual threads without allowing them to exceed the turn budget.
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentBoundedCallExecutor {

    /**
     * Executes one blocking stage within the remaining whole-turn budget.
     *
     * @param work blocking work
     * @param budget shared turn budget
     * @param stage stable trace stage
     * @param <T> result type
     * @return completed result
     * @throws AgentTurnBudget.UnavailableException when cancelled or timed out
     * @throws AgentStageInterruptedException when the calling thread is interrupted
     */
    public <T> T execute(Callable<T> work, AgentTurnBudget budget, String stage) {
        Duration remaining = budget.remaining(stage, budget.totalBudget());
        Map<String, String> callingContext = MDC.getCopyOfContextMap();
        FutureTask<T> task = new FutureTask<>(() -> callWithMdc(work, callingContext));
        Thread worker = Thread.ofVirtual().name("agent-" + stage + "-").start(task);
        try {
            T result = task.get(Math.max(1, remaining.toNanos()), TimeUnit.NANOSECONDS);
            budget.check(stage);
            return result;
        } catch (TimeoutException exception) {
            task.cancel(true);
            throw unavailable(budget, stage);
        } catch (InterruptedException exception) {
            task.cancel(true);
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw new AgentStageInterruptedException(stage, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof AgentTurnBudget.UnavailableException unavailable) {
                throw unavailable;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Agent stage failed: " + stage, cause);
        }
    }

    private <T> T callWithMdc(Callable<T> work, Map<String, String> callingContext) throws Exception {
        Map<String, String> workerContext = MDC.getCopyOfContextMap();
        try {
            restoreMdc(callingContext);
            return work.call();
        } finally {
            restoreMdc(workerContext);
        }
    }

    private void restoreMdc(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(context);
    }

    private AgentTurnBudget.UnavailableException unavailable(
            AgentTurnBudget budget,
            String stage) {
        return AgentTurnBudget.unavailableForStage(
                budget.isCancelled()
                        ? AgentTurnBudget.UnavailableReason.CANCELLED
                        : AgentTurnBudget.UnavailableReason.TIMEOUT,
                stage);
    }
}
