package com.chtholly.agent.ws;

import com.chtholly.common.tracing.CorrelationIdSupport;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executes WebSocket background work while restoring the submitting MDC.
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentWebSocketTaskExecutor {

    private final Executor delegate;
    private final ExecutorService ownedExecutor;

    /** Creates the production virtual-thread executor. */
    public AgentWebSocketTaskExecutor() {
        this(Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    AgentWebSocketTaskExecutor(Executor delegate) {
        this(delegate, false);
    }

    private AgentWebSocketTaskExecutor(Executor delegate, boolean owned) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.ownedExecutor = owned && delegate instanceof ExecutorService service
                ? service
                : null;
    }

    void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        Map<String, String> context = CorrelationIdSupport.copyContext();
        delegate.execute(() -> CorrelationIdSupport.runWithContext(
                context, command));
    }

    /** Stops the internally owned executor during application shutdown. */
    @PreDestroy
    public void close() {
        if (ownedExecutor != null) {
            ownedExecutor.close();
        }
    }
}
