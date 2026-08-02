package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.AgentToolParamValidator;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.observability.AgentToolDiagnostics;
import com.chtholly.agent.observability.AgentTraceSanitizer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Validates and executes agent tools on virtual threads with bounded runtime.
 *
 * <p>The production component owns one executor for its bean lifetime. Tests may inject an
 * externally managed executor to observe cancellation without transferring ownership.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentToolExecutor {

    private static final String TOOL_TIMEOUT_MESSAGE = "Tool execution timed out";
    private static final String TOOL_VALIDATION_ERROR = "TOOL_VALIDATION_ERROR";
    private static final String TOOL_TIMEOUT = "TOOL_TIMEOUT";
    private static final String TOOL_EXECUTION_ERROR = "TOOL_EXECUTION_ERROR";
    private static final String TOOL_INTERRUPTED = "TOOL_INTERRUPTED";
    private static final long DIAGNOSTICS_TIMEOUT_MILLIS = 250;

    private final AgentProperties properties;
    private final AgentDomainConfig agentDomainConfig;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    /**
     * Creates the production executor backed by virtual threads.
     *
     * @param properties agent runtime properties
     * @param agentDomainConfig agent domain messages
     */
    @Autowired
    public AgentToolExecutor(AgentProperties properties, AgentDomainConfig agentDomainConfig) {
        this(properties, agentDomainConfig, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    AgentToolExecutor(
            AgentProperties properties,
            AgentDomainConfig agentDomainConfig,
            ExecutorService executor,
            boolean ownsExecutor) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.agentDomainConfig = Objects.requireNonNull(agentDomainConfig, "agentDomainConfig");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Executes one tool after validating its declared input schema.
     *
     * @param tool tool to execute
     * @param input tool input parameters
     * @param userId authenticated user identifier
     * @return structured execution result and observation text
     */
    public AgentToolResult execute(AgentTool tool, Map<String, Object> input, long userId) {
        return execute(
                tool,
                input,
                userId,
                Duration.ofSeconds(Math.max(1, properties.getToolTimeoutSeconds())));
    }

    /**
     * Executes one validated tool within both the configured limit and turn remainder.
     *
     * @param tool tool to execute
     * @param input tool input parameters
     * @param userId authenticated user identifier
     * @param turnRemainder remaining whole-turn budget
     * @return structured execution result and observation text
     */
    public AgentToolResult execute(
            AgentTool tool,
            Map<String, Object> input,
            long userId,
            Duration turnRemainder) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(input, "input");

        Map<String, ParamDef> parameterSchema = tool.parameterSchema();
        Optional<String> validationError = AgentToolParamValidator.validate(input, parameterSchema);
        if (validationError.isPresent()) {
            return result(tool, parameterSchema, input, validationError.get(),
                    AgentToolResult.Status.VALIDATION_ERROR, TOOL_VALIDATION_ERROR);
        }

        Duration timeout = effectiveTimeout(turnRemainder);
        Future<String> future = executor.submit(() -> tool.execute(input, userId));
        try {
            return result(tool, parameterSchema, input,
                    future.get(timeout.toNanos(), TimeUnit.NANOSECONDS),
                    AgentToolResult.Status.SUCCESS, "");
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Tool {} execution timed out (>{}ms)", safeToolName(tool), timeout.toMillis());
            return result(tool, parameterSchema, input, TOOL_TIMEOUT_MESSAGE,
                    AgentToolResult.Status.TIMEOUT, TOOL_TIMEOUT);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String rawMessage = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            String message = AgentTraceSanitizer.safeMessage(rawMessage);
            log.warn("Tool {} execution failed ({})", safeToolName(tool), cause.getClass().getSimpleName());
            String observation = agentDomainConfig.render(
                    agentDomainConfig.errors().toolFailed(),
                    "message",
                    message);
            return result(tool, parameterSchema, input, observation,
                    AgentToolResult.Status.ERROR, TOOL_EXECUTION_ERROR);
        } catch (InterruptedException e) {
            future.cancel(true);
            log.warn("Tool {} execution interrupted", safeToolName(tool));
            Thread.currentThread().interrupt();
            return result(tool, parameterSchema, input, agentDomainConfig.errors().toolInterrupted(),
                    AgentToolResult.Status.INTERRUPTED, TOOL_INTERRUPTED);
        }
    }

    private AgentToolResult result(
            AgentTool tool,
            Map<String, ParamDef> parameterSchema,
            Map<String, Object> input,
            String observation,
            AgentToolResult.Status status,
            String errorCode) {
        AgentToolDiagnostics diagnostics = null;
        Future<AgentToolDiagnostics> diagnosticsFuture = null;
        try {
            diagnosticsFuture = executor.submit(() -> tool.traceDiagnostics(input, observation));
            diagnostics = diagnosticsFuture.get(DIAGNOSTICS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException diagnosticsFailure) {
            log.debug("Tool {} diagnostics projection failed", safeToolName(tool));
        } catch (InterruptedException diagnosticsInterrupted) {
            Thread.currentThread().interrupt();
            log.debug("Tool {} diagnostics projection interrupted", safeToolName(tool));
        } catch (RuntimeException diagnosticsFailure) {
            log.debug("Tool {} diagnostics projection unavailable", safeToolName(tool));
        } finally {
            if (diagnosticsFuture != null && !diagnosticsFuture.isDone()) {
                diagnosticsFuture.cancel(true);
            }
        }
        if (diagnostics == null) {
            diagnostics = fallbackDiagnostics(tool, parameterSchema, input, observation);
        }
        diagnostics = diagnostics.withErrorCode(errorCode);
        return new AgentToolResult(observation, status, errorCode, diagnostics);
    }

    private AgentToolDiagnostics fallbackDiagnostics(
            AgentTool tool,
            Map<String, ParamDef> parameterSchema,
            Map<String, Object> input,
            String observation) {
        try {
            return AgentToolDiagnostics.standard(
                    safeToolName(tool), parameterSchema, input, observation);
        } catch (Throwable ignored) {
            return AgentToolDiagnostics.fallback(safeToolName(tool), observation);
        }
    }

    private String safeToolName(AgentTool tool) {
        try {
            String name = tool.name();
            return name == null || name.isBlank() ? "unknown" : name;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    private Duration effectiveTimeout(Duration turnRemainder) {
        Duration configured = Duration.ofSeconds(Math.max(1, properties.getToolTimeoutSeconds()));
        if (turnRemainder == null || turnRemainder.isZero() || turnRemainder.isNegative()) {
            return Duration.ofNanos(1);
        }
        return turnRemainder.compareTo(configured) < 0 ? turnRemainder : configured;
    }
}
