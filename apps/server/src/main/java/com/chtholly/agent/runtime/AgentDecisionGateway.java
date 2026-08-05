package com.chtholly.agent.runtime;

import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentObservationService;
import io.micrometer.observation.Observation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** Executes one traced model decision with bounded transient-failure retry policy. */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentDecisionGateway {

    private final AgentLlmInvoker llmInvoker;
    private final AgentObservationService observationService;

    /**
     * Creates the model decision gateway.
     *
     * @param llmInvoker model invocation adapter
     */
    @Autowired
    public AgentDecisionGateway(
            AgentLlmInvoker llmInvoker,
            AgentObservationService observationService) {
        this.llmInvoker = llmInvoker;
        this.observationService = observationService;
    }

    /**
     * Executes one decision, retrying at most once only for classified transient failures.
     *
     * @param systemPrompt immutable loop system prompt
     * @param userPrompt current transcript prompt
     * @param inputChars complete input character count
     * @param step zero-based loop step
     * @param trace execution trace
     * @param turnBudget optional whole-turn budget
     * @param agentSpan parent observation span for per-attempt model calls
     * @return raw model decision
     * @throws Exception when the terminal attempt fails
     */
    public String decide(
            String systemPrompt,
            String userPrompt,
            int inputChars,
            int step,
            AgentExecutionTrace trace,
            AgentTurnBudget turnBudget,
            Observation agentSpan) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            Observation llmSpan = observationService.startLlmSpan(agentSpan, llmInvoker.modelName());
            long attemptStartedAt = System.currentTimeMillis();
            long budgetBeforeMs = remainingBudgetMs(turnBudget);
            try {
                String output = turnBudget == null
                        ? llmInvoker.call(systemPrompt, userPrompt, 0.1, 1024)
                        : llmInvoker.call(
                                systemPrompt,
                                userPrompt,
                                0.1,
                                1024,
                                turnBudget.remaining(
                                        "loop_llm",
                                        Duration.ofSeconds(llmInvoker.timeoutSeconds())));
                trace.recordLlmCall(
                        step,
                        "LOOP_DECISION",
                        llmInvoker.modelName(),
                        "SUCCESS",
                        "",
                        attempt + 1,
                        budgetBeforeMs,
                        remainingBudgetMs(turnBudget),
                        elapsedMillis(attemptStartedAt),
                        inputChars,
                        output == null ? 0 : output.length(),
                        null,
                        AgentExecutionTrace.LlmExchange.success(
                                systemPrompt, userPrompt, output));
                observationService.finishSpan(
                        llmSpan,
                        AgentSpanAttributes.llm("ok"),
                        Map.of("llm.attempt", Integer.toString(attempt + 1)));
                return output;
            } catch (Exception exception) {
                Failure traceFailure = classifyFailure(exception, turnBudget);
                trace.recordLlmCall(
                        step,
                        "LOOP_DECISION",
                        llmInvoker.modelName(),
                        traceFailure.status(),
                        traceFailure.errorCode(),
                        attempt + 1,
                        budgetBeforeMs,
                        remainingBudgetMs(turnBudget),
                        elapsedMillis(attemptStartedAt),
                        inputChars,
                        0,
                        null,
                        AgentExecutionTrace.LlmExchange.failure(
                                systemPrompt, userPrompt, "", exception));
                observationService.finishSpanError(
                        llmSpan,
                        traceFailure.spanErrorName(),
                        AgentSpanAttributes.llm(traceFailure.spanStatus()),
                        Map.of("llm.attempt", Integer.toString(attempt + 1)));
                Exception terminalFailure = terminalFailure(exception);
                if (terminalFailure != null) {
                    throw terminalFailure;
                }
                if (attempt == 0 && isRetryable(exception)) {
                    log.warn(
                            "Agent LLM call failed transiently; retrying once: {}",
                            exception.getClass().getName());
                    continue;
                }
                throw exception;
            }
        }
        throw new IllegalStateException("unreachable model retry state");
    }

    String modelName() {
        return llmInvoker.modelName();
    }

    int timeoutSeconds() {
        return llmInvoker.timeoutSeconds();
    }

    private boolean isRetryable(Throwable failure) {
        List<Throwable> causes = causeChain(failure);
        for (Throwable current : causes) {
            if (current instanceof AgentTurnBudget.UnavailableException
                    || current instanceof InterruptedException
                    || current instanceof TimeoutException) {
                return false;
            }
        }
        for (Throwable current : causes) {
            String className = current.getClass().getName();
            String message = current.getMessage() == null
                    ? ""
                    : current.getMessage().toLowerCase(Locale.ROOT);
            if (className.contains("TransientAiException")
                    || className.contains("ResourceAccessException")
                    || className.contains("ConnectException")
                    || className.contains("SocketException")
                    || message.contains("429")
                    || message.contains("rate limit")
                    || message.contains("too many requests")
                    || message.contains("temporarily unavailable")
                    || message.contains("connection reset")
                    || message.contains("connection refused")) {
                return true;
            }
        }
        return false;
    }

    private Failure classifyFailure(Throwable failure, AgentTurnBudget turnBudget) {
        List<Throwable> causes = causeChain(failure);
        for (Throwable current : causes) {
            if (current instanceof AgentTurnBudget.UnavailableException unavailable) {
                return unavailable.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                        ? new Failure("CANCELLED", "TURN_CANCELLED")
                        : new Failure("TIMEOUT", "TURN_TIMEOUT");
            }
        }
        for (Throwable current : causes) {
            if (current instanceof InterruptedException) {
                return turnBudget != null && turnBudget.isCancelled()
                        ? new Failure("CANCELLED", "TURN_CANCELLED")
                        : new Failure("INTERRUPTED", "LLM_INTERRUPTED");
            }
        }
        for (Throwable current : causes) {
            if (current instanceof TimeoutException) {
                return turnBudget != null && turnBudget.isExpired()
                        ? new Failure("TIMEOUT", "TURN_TIMEOUT")
                        : new Failure("TIMEOUT", "LLM_TIMEOUT");
            }
        }
        return isRetryable(failure)
                ? new Failure("ERROR", "LLM_TRANSIENT_ERROR")
                : new Failure("ERROR", "LLM_ERROR");
    }

    private Exception terminalFailure(Throwable failure) {
        List<Throwable> causes = causeChain(failure);
        for (Throwable current : causes) {
            if (current instanceof AgentTurnBudget.UnavailableException unavailable) {
                return unavailable;
            }
        }
        for (Throwable current : causes) {
            if (current instanceof InterruptedException interrupted) {
                return interrupted;
            }
        }
        for (Throwable current : causes) {
            if (current instanceof TimeoutException timeout) {
                return timeout;
            }
        }
        return null;
    }

    private List<Throwable> causeChain(Throwable failure) {
        List<Throwable> causes = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && seen.add(current)) {
            causes.add(current);
            current = current.getCause();
        }
        return causes;
    }

    private long remainingBudgetMs(AgentTurnBudget turnBudget) {
        if (turnBudget == null) {
            return 0;
        }
        long totalNanos = turnBudget.totalBudget().toNanos();
        long elapsedNanos = turnBudget.elapsed().toNanos();
        return Math.max(0, totalNanos - elapsedNanos) / 1_000_000L;
    }

    private long elapsedMillis(long startedAtMs) {
        return Math.max(0, System.currentTimeMillis() - startedAtMs);
    }

    private record Failure(String status, String errorCode) {
        private String spanErrorName() {
            return errorCode.toLowerCase(Locale.ROOT);
        }

        private String spanStatus() {
            return switch (status) {
                case "SUCCESS" -> "ok";
                case "CANCELLED" -> "cancelled";
                case "TIMEOUT" -> "timeout";
                case "INTERRUPTED" -> "interrupted";
                default -> "error";
            };
        }
    }
}
