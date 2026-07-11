package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.AgentToolParamValidator;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(input, "input");

        Optional<String> validationError = AgentToolParamValidator.validate(input, tool.parameterSchema());
        if (validationError.isPresent()) {
            return new AgentToolResult(validationError.get(), AgentToolResult.Status.VALIDATION_ERROR);
        }

        int timeoutSeconds = Math.max(1, properties.getToolTimeoutSeconds());
        Future<String> future = executor.submit(() -> tool.execute(input, userId));
        try {
            return new AgentToolResult(
                    future.get(timeoutSeconds, TimeUnit.SECONDS),
                    AgentToolResult.Status.SUCCESS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Tool {} execution timed out (>{}s)", tool.name(), timeoutSeconds);
            return new AgentToolResult(TOOL_TIMEOUT_MESSAGE, AgentToolResult.Status.TIMEOUT);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            log.warn("Tool {} execution failed: {}", tool.name(), message, cause);
            String observation = agentDomainConfig.render(
                    agentDomainConfig.errors().toolFailed(),
                    "message",
                    message);
            return new AgentToolResult(observation, AgentToolResult.Status.ERROR);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new AgentToolResult(
                    agentDomainConfig.errors().toolInterrupted(),
                    AgentToolResult.Status.INTERRUPTED);
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }
}
