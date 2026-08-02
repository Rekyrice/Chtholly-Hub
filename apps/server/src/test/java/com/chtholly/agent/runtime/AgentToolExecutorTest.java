package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentErrorMessages;
import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.observability.AgentToolDiagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
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
        assertThat(result.errorCode()).isEqualTo("TOOL_VALIDATION_ERROR");
        assertThat(result.diagnostics().operation()).isEqualTo("validated");
        assertThat(result.diagnostics().errorCode()).isEqualTo("TOOL_VALIDATION_ERROR");
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
        assertThat(result.errorCode()).isEmpty();
        assertThat(result.diagnostics().operation()).isEqualTo("success");
        assertThat(result.diagnostics().provider()).isEqualTo("internal");
        assertThat(result.diagnostics().sourcePolicy()).isEqualTo("unspecified");
        assertThat(result.diagnostics().sanitizedInput()).isEmpty();
        assertThat(result.diagnostics().outputPreview()).isEqualTo("original observation");
        assertThat(result.diagnostics().errorCode()).isEmpty();
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
        assertThat(result.errorCode()).isEqualTo("TOOL_TIMEOUT");
        assertThat(result.diagnostics().errorCode()).isEqualTo("TOOL_TIMEOUT");
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void toolUsesTurnRemainderWhenItIsShorterThanConfiguredTimeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AgentTool blockingTool = tool("blocking", Map.of(), input -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
                return "late";
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        });

        long startedAt = System.nanoTime();
        AgentToolResult result = executor(5).execute(
                blockingTool, Map.of(), 7L, Duration.ofMillis(100));

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.status()).isEqualTo(AgentToolResult.Status.TIMEOUT);
        assertThat(result.errorCode()).isEqualTo("TOOL_TIMEOUT");
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
        assertThat(result.errorCode()).isEqualTo("TOOL_EXECUTION_ERROR");
        assertThat(result.diagnostics().errorCode()).isEqualTo("TOOL_EXECUTION_ERROR");
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
        assertThat(resultRef.get().errorCode()).isEqualTo("TOOL_INTERRUPTED");
        assertThat(resultRef.get().diagnostics().errorCode()).isEqualTo("TOOL_INTERRUPTED");
        assertThat(callerInterrupted).isTrue();
        assertThat(workerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void toolCanExtendStandardDiagnosticsWithoutExposingInternalInput() {
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "custom";
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public Map<String, ParamDef> parameterSchema() {
                return Map.of("query", new ParamDef("Query", String.class, true));
            }

            @Override
            public String execute(Map<String, Object> input, long userId) {
                return "custom output";
            }

            @Override
            public AgentToolDiagnostics traceDiagnostics(Map<String, Object> input, String observation) {
                return AgentTool.super.traceDiagnostics(input, observation)
                        .withProvider("custom-provider")
                        .withSourcePolicy("custom-policy")
                        .withResultCount(2)
                        .withSelectedIds(List.of("one", "two"));
            }
        };

        AgentToolResult result = executor(5).execute(tool, Map.of(
                "query", "safe query",
                "_userQuestion", "private question",
                "_conversationHistory", "private history"), 7L);

        assertThat(result.status()).isEqualTo(AgentToolResult.Status.SUCCESS);
        assertThat(result.diagnostics().provider()).isEqualTo("custom-provider");
        assertThat(result.diagnostics().sourcePolicy()).isEqualTo("custom-policy");
        assertThat(result.diagnostics().resultCount()).isEqualTo(2);
        assertThat(result.diagnostics().selectedIds()).containsExactly("one", "two");
        assertThat(result.diagnostics().sanitizedInput()).containsOnly(Map.entry("query", "safe query"));
    }

    @Test
    void diagnosticsFailureFallsBackWithoutChangingSuccessfulToolResult() {
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "diagnostics-failure";
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public Map<String, ParamDef> parameterSchema() {
                return Map.of("query", new ParamDef("Query", String.class, true));
            }

            @Override
            public String execute(Map<String, Object> input, long userId) {
                return "successful output";
            }

            @Override
            public AgentToolDiagnostics traceDiagnostics(Map<String, Object> input, String observation) {
                throw new IllegalStateException("diagnostics-secret");
            }
        };

        AgentToolResult result = executor(5).execute(tool, Map.of(
                "query", "safe query",
                "_conversationHistory", "private history"), 7L);

        assertThat(result.status()).isEqualTo(AgentToolResult.Status.SUCCESS);
        assertThat(result.observation()).isEqualTo("successful output");
        assertThat(result.errorCode()).isEmpty();
        assertThat(result.diagnostics().operation()).isEqualTo("diagnostics-failure");
        assertThat(result.diagnostics().sanitizedInput()).containsOnly(Map.entry("query", "safe query"));
        assertThat(result.diagnostics().toString()).doesNotContain("diagnostics-secret", "private history");
    }

    @Test
    void blockingDiagnosticsAreCancelledWithoutDelayingSuccessfulToolResult() throws Exception {
        CountDownLatch diagnosticsStarted = new CountDownLatch(1);
        CountDownLatch diagnosticsInterrupted = new CountDownLatch(1);
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "blocking-diagnostics";
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public String execute(Map<String, Object> input, long userId) {
                return "successful output";
            }

            @Override
            public AgentToolDiagnostics traceDiagnostics(Map<String, Object> input, String observation) {
                diagnosticsStarted.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException exception) {
                    diagnosticsInterrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return AgentTool.super.traceDiagnostics(input, observation);
            }
        };

        long startedAt = System.nanoTime();
        AgentToolResult result = executor(5).execute(tool, Map.of(), 7L);

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
        assertThat(result.status()).isEqualTo(AgentToolResult.Status.SUCCESS);
        assertThat(result.observation()).isEqualTo("successful output");
        assertThat(diagnosticsStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(diagnosticsInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void diagnosticsErrorDoesNotChangeSuccessfulToolResult() {
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "diagnostics-error";
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public String execute(Map<String, Object> input, long userId) {
                return "successful output";
            }

            @Override
            public AgentToolDiagnostics traceDiagnostics(Map<String, Object> input, String observation) {
                throw new AssertionError("must-not-escape");
            }
        };

        AgentToolResult result = executor(5).execute(tool, Map.of(), 7L);

        assertThat(result.status()).isEqualTo(AgentToolResult.Status.SUCCESS);
        assertThat(result.observation()).isEqualTo("successful output");
        assertThat(result.diagnostics().toString()).doesNotContain("must-not-escape");
    }

    @Test
    void errorDiagnosticsDoNotPersistExceptionUrlsHeadersOrConversationHistory() {
        AgentTool failingTool = tool("unsafe-failure", Map.of(), input -> {
            throw new IllegalStateException(
                    "GET https://api.example.test/private?token=url-secret "
                            + "Authorization: Bearer header-secret "
                            + "_conversationHistory=private-history");
        });

        AgentToolResult result = executor(5).execute(failingTool, Map.of(
                "_conversationHistory", "private-input-history"), 7L);

        assertThat(result.status()).isEqualTo(AgentToolResult.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo("TOOL_EXECUTION_ERROR");
        assertThat(result.diagnostics().toString()).doesNotContain(
                "https://api.example.test",
                "url-secret",
                "header-secret",
                "private-history",
                "private-input-history");
    }

    @Test
    void legacyResultConstructorRemainsCompatible() {
        AgentToolResult result = new AgentToolResult("legacy", AgentToolResult.Status.SUCCESS);

        assertThat(result.observation()).isEqualTo("legacy");
        assertThat(result.status()).isEqualTo(AgentToolResult.Status.SUCCESS);
        assertThat(result.errorCode()).isEmpty();
        assertThat(result.diagnostics()).isNotNull();
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
