package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentErrorMessages;
import com.chtholly.agent.config.AgentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolExecutorTest {

    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        workerExecutor.shutdownNow();
    }

    @Test
    void missingRequiredParameterReturnsValidationErrorWithoutCallingTool() {
        AtomicInteger calls = new AtomicInteger();
        AgentTool tool = tool("validated", Map.of(
                "keyword", new ParamDef("Search keyword", String.class, true)),
                input -> {
                    calls.incrementAndGet();
                    return "unused";
                });

        AgentToolResult result = executor(5).execute(tool, Map.of(), 7L);

        assertThat(result.status()).isEqualTo(AgentToolResult.Status.VALIDATION_ERROR);
        assertThat(result.observation()).isEqualTo("Missing required parameter: keyword");
        assertThat(calls).hasValue(0);
    }

    @Test
    void successfulToolReturnsOriginalObservation() {
        AgentToolResult result = executor(5).execute(
                tool("success", Map.of(), input -> "original observation"),
                Map.of("keyword", "re0"),
                7L);

        assertThat(result.status()).isEqualTo(AgentToolResult.Status.SUCCESS);
        assertThat(result.observation()).isEqualTo("original observation");
    }

    @Test
    void timedOutToolIsCancelledAndWorkerIsInterrupted() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AgentTool blockingTool = tool("blocking", Map.of(), input -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
                return "late";
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        });

        AgentToolResult result = executor(1).execute(blockingTool, Map.of(), 7L);

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.status()).isEqualTo(AgentToolResult.Status.TIMEOUT);
        assertThat(result.observation()).isEqualTo("Tool execution timed out");
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void toolFailureReturnsRenderedErrorWithCauseMessage() {
        AgentTool failingTool = tool("failing", Map.of(), input -> {
            throw new IllegalStateException("boom");
        });

        AgentToolResult result = executor(5).execute(failingTool, Map.of(), 7L);

        assertThat(result.status()).isEqualTo(AgentToolResult.Status.ERROR);
        assertThat(result.observation()).isEqualTo("Tool failed: boom");
    }

    @Test
    void interruptedCallerRestoresFlagAndCancelsWorker() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        AtomicReference<AgentToolResult> resultRef = new AtomicReference<>();
        AtomicBoolean callerInterrupted = new AtomicBoolean();
        AgentTool blockingTool = tool("blocking", Map.of(), input -> {
            workerStarted.countDown();
            try {
                Thread.sleep(30_000);
                return "late";
            } catch (InterruptedException e) {
                workerInterrupted.countDown();
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        });
        Thread caller = Thread.ofPlatform().start(() -> {
            resultRef.set(executor(5).execute(blockingTool, Map.of(), 7L));
            callerInterrupted.set(Thread.currentThread().isInterrupted());
        });

        assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(2_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(resultRef.get().status()).isEqualTo(AgentToolResult.Status.INTERRUPTED);
        assertThat(resultRef.get().observation()).isEqualTo("Tool execution interrupted");
        assertThat(callerInterrupted).isTrue();
        assertThat(workerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    private AgentToolExecutor executor(int timeoutSeconds) {
        AgentProperties properties = new AgentProperties();
        properties.setToolTimeoutSeconds(timeoutSeconds);
        return new AgentToolExecutor(properties, domainConfig(), workerExecutor, false);
    }

    private AgentDomainConfig domainConfig() {
        return new AgentDomainConfig(null, new AgentErrorMessages(
                "Question empty",
                "Model response timeout",
                "Model call failed",
                "Model call interrupted",
                "Response timeout",
                "Response failed",
                "Max steps",
                "Unknown tool",
                "Tool failed: {message}",
                "Tool execution interrupted",
                "No result"), null, null);
    }

    private AgentTool tool(String name, Map<String, ParamDef> schema, ToolAction action) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public Map<String, ParamDef> parameterSchema() {
                return schema;
            }

            @Override
            public String execute(Map<String, Object> input, long userId) {
                return action.execute(input);
            }
        };
    }

    @FunctionalInterface
    private interface ToolAction {
        String execute(Map<String, Object> input);
    }
}
