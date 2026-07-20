package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.AgentJsonExtractor;
import com.chtholly.agent.AgentTool;
import com.chtholly.agent.config.AgentContextLabels;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentErrorMessages;
import com.chtholly.agent.config.AgentSystemPromptConfig;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentObservationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentLoopExecutorTest {

    @Mock
    private AgentLlmInvoker llmInvoker;
    @Mock
    private AgentToolExecutor toolExecutor;
    @Mock
    private AgentObservationService observationService;
    @Mock
    private Observation agentSpan;
    @Mock
    private Observation childSpan;

    private ObjectMapper objectMapper;
    private AgentDomainConfig domainConfig;
    private AgentLoopExecutor executor;
    private List<AgentEvent> events;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        domainConfig = domainConfig();
        lenient().when(llmInvoker.modelName()).thenReturn("test-model");
        lenient().when(llmInvoker.timeoutSeconds()).thenReturn(3);
        lenient().when(observationService.startLlmSpan(any(), anyString())).thenReturn(childSpan);
        lenient().when(observationService.startToolSpan(any(), anyString())).thenReturn(childSpan);
        executor = new AgentLoopExecutor(
                llmInvoker,
                toolExecutor,
                new AgentJsonExtractor(objectMapper),
                objectMapper,
                observationService,
                domainConfig);
        events = new ArrayList<>();
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void finalActionReturnsReadyWithInitialTranscriptAndOnlyThinkEvent() throws Exception {
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"final\",\"answer\":\"draft\"}");
        AgentExecutionTrace trace = trace(3);

        AgentLoopResult result = executor.execute(
                request(Map.of(), 3), trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        assertThat(result.transcript()).containsExactly(
                "Earlier conversation",
                "## Current question\nUser: What happened?");
        assertThat(eventTypes()).containsExactly("think");
        assertThat(events.getFirst().data().path("content").asText()).isEqualTo("Preparing final answer");
        verify(toolExecutor, never()).execute(any(), anyMap(), anyLong());
        verify(observationService).startLlmSpan(agentSpan, "test-model");
        assertThat(result.finalStepIndex()).isZero();
        assertThat(result.finalDecisionLlmMs()).isGreaterThanOrEqualTo(0);
        assertThat(trace.getStepActions()).isEmpty();
    }

    @Test
    void toolActionInjectsContextFeedsObservationAndRecordsTraceBeforeFinal() throws Exception {
        AgentTool tool = tool("search");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"search\",\"input\":{\"query\":\"re0\"}}")
                .thenReturn("{\"action\":\"final\"}");
        when(toolExecutor.execute(any(), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult("tool result", AgentToolResult.Status.SUCCESS));
        AgentExecutionTrace trace = trace(3);

        AgentLoopResult result = executor.execute(
                request(Map.of(tool.name(), tool), 3), trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        assertThat(eventTypes()).containsExactly("think", "act", "observe", "think");
        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(toolExecutor).execute(any(), inputCaptor.capture(), anyLong());
        assertThat(inputCaptor.getValue())
                .containsEntry("query", "re0")
                .containsEntry("_userQuestion", "What happened?")
                .containsEntry("_conversationHistory", "Earlier conversation");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvoker, times(2)).call(anyString(), promptCaptor.capture(), anyDouble(), anyInt());
        assertThat(promptCaptor.getAllValues().get(1)).contains("Observation: tool result");
        assertThat(trace.getStepActions()).containsExactly("search");
        assertThat(trace.getToolsCalled()).containsExactly("search");
        JsonNode toolCalls = objectMapper.valueToTree(trace.toPayloadMap().get("toolCalls"));
        assertThat(toolCalls.path(0).path("success").asBoolean()).isTrue();
    }

    @Test
    void invalidJsonEmitsConfiguredParseEventsAndRetries() throws Exception {
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("not-json")
                .thenReturn("{\"action\":\"final\"}");
        AgentExecutionTrace trace = trace(3);

        AgentLoopResult result = executor.execute(
                request(Map.of(), 3), trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        assertThat(eventTypes()).containsExactly("think", "observe", "think");
        assertThat(events.get(0).data().path("content").asText()).isEqualTo("JSON parse failed");
        assertThat(events.get(1).data().path("content").asText()).isEqualTo("PARSE_ERROR_ORIGINAL");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvoker, times(2)).call(anyString(), promptCaptor.capture(), anyDouble(), anyInt());
        assertThat(promptCaptor.getAllValues().get(1)).contains("Observation: PARSE_ERROR_ORIGINAL");
        assertThat(trace.getStepActions()).containsExactly("parse_error");
    }

    @Test
    void unknownToolEmitsActAndObservationThenRetriesWithoutExecution() throws Exception {
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"missing\",\"input\":{}}")
                .thenReturn("{\"action\":\"final\"}");
        AgentExecutionTrace trace = trace(3);

        AgentLoopResult result = executor.execute(
                request(Map.of(), 3), trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        assertThat(eventTypes()).containsExactly("think", "act", "observe", "think");
        assertThat(events.get(2).data().path("content").asText()).isEqualTo("Unknown tool: missing");
        verify(toolExecutor, never()).execute(any(), anyMap(), anyLong());
        assertThat(trace.getStepActions()).containsExactly("unknown_tool");
    }

    @Test
    void repeatedToolStopsAtMaxStepsWithExactErrorAndTraceTerminalState() throws Exception {
        AgentTool tool = tool("search");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"search\",\"input\":{}}");
        when(toolExecutor.execute(any(), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult("again", AgentToolResult.Status.SUCCESS));
        AgentExecutionTrace trace = trace(2);

        AgentLoopResult result = executor.execute(
                request(Map.of(tool.name(), tool), 2), trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.MAX_STEPS);
        assertThat(result.errorMessage()).isEqualTo("Reached max steps: 2");
        assertThat(eventTypes()).containsExactly(
                "think", "act", "observe", "think", "act", "observe", "error");
        assertThat(events.getLast().data().path("message").asText()).isEqualTo("Reached max steps: 2");
        assertThat(trace.getTerminatedBy()).isEqualTo("max_steps");
        assertThat(trace.getErrorMessage()).isEqualTo("Reached max steps: 2");
        assertThat(trace.getStepActions()).containsExactly("search", "search");
    }

    @Test
    void llmTimeoutReturnsTimeoutAndMarksStreamAndTrace() throws Exception {
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenThrow(new TimeoutException("slow"));
        AgentExecutionTrace trace = trace(2);

        AgentLoopResult result = executor.execute(
                request(Map.of(), 2), trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.LLM_TIMEOUT);
        assertThat(result.errorMessage()).isEqualTo("MODEL_TIMEOUT");
        assertThat(eventTypes()).containsExactly("error");
        assertThat(events.getFirst().data().path("message").asText()).isEqualTo("MODEL_TIMEOUT");
        assertThat(trace.getTerminatedBy()).isEqualTo("timeout");
        assertThat(trace.getErrorMessage()).isEqualTo("MODEL_TIMEOUT");
        verify(observationService).finishSpanError(
                eq(childSpan), eq("llm_timeout"), anyMap(), anyMap());
    }

    @Test
    void llmExceptionReturnsErrorAndMarksTrace() throws Exception {
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("broken"));
        AgentExecutionTrace trace = trace(2);

        AgentLoopResult result = executor.execute(
                request(Map.of(), 2), trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.LLM_ERROR);
        assertThat(result.errorMessage()).isEqualTo("MODEL_FAILED");
        assertThat(eventTypes()).containsExactly("error");
        assertThat(trace.getTerminatedBy()).isEqualTo("error");
        assertThat(trace.getErrorMessage()).isEqualTo("MODEL_FAILED");
        verify(observationService).finishSpanError(
                eq(childSpan), eq("llm_error"), anyMap(), anyMap());
    }

    @Test
    void interruptedLlmReturnsDedicatedTerminalStatusAndPreservesInterruptFlag() throws Exception {
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenThrow(new InterruptedException("caller interrupted"));
        AgentExecutionTrace trace = trace(3);

        try {
            AgentLoopResult result = executor.execute(
                    request(Map.of(), 3), trace, agentSpan, events::add);

            assertThat(result.status()).isEqualTo(AgentLoopResult.Status.LLM_INTERRUPTED);
            assertThat(result.errorMessage()).isEqualTo("MODEL_INTERRUPTED");
            assertThat(eventTypes()).containsExactly("error");
            assertThat(trace.getStepActions()).isEmpty();
            assertThat(trace.getTerminatedBy()).isEqualTo("error");
            assertThat(trace.getErrorMessage()).isEqualTo("MODEL_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(llmInvoker, times(1)).call(anyString(), anyString(), anyDouble(), anyInt());
            verify(observationService).finishSpanError(
                    eq(childSpan), eq("llm_interrupted"), anyMap(), anyMap());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptedToolRecordsStepEmitsErrorAndStopsWithInterruptFlagPreserved() throws Exception {
        AgentTool tool = tool("search");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"search\",\"input\":{}}");
        when(toolExecutor.execute(any(), anyMap(), anyLong())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return new AgentToolResult("interrupted observation", AgentToolResult.Status.INTERRUPTED);
        });
        AgentExecutionTrace trace = trace(3);

        AgentLoopResult result = executor.execute(
                request(Map.of(tool.name(), tool), 3), trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.TOOL_INTERRUPTED);
        assertThat(eventTypes()).containsExactly("think", "act", "observe", "error");
        assertThat(trace.getStepActions()).containsExactly("search");
        assertThat(trace.getTerminatedBy()).isEqualTo("error");
        assertThat(trace.getErrorMessage()).isEqualTo("TOOL_INTERRUPTED");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(llmInvoker, times(1)).call(anyString(), anyString(), anyDouble(), anyInt());
        verify(observationService).finishSpanError(
                eq(childSpan), eq("tool_interrupted"), anyMap(), anyMap());
    }

    @Test
    void timedOutBangumiToolMarksFailedErrorSpanAndAddsGuidance() throws Exception {
        AgentTool tool = tool("bangumi_search");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"bangumi_search\",\"input\":{}}")
                .thenReturn("{\"action\":\"final\"}");
        when(toolExecutor.execute(any(), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult("timed out", AgentToolResult.Status.TIMEOUT));
        AgentExecutionTrace trace = trace(3);

        AgentLoopResult result = executor.execute(
                request(Map.of(tool.name(), tool), 3), trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        assertThat(events.stream()
                .filter(event -> "observe".equals(event.type()))
                .findFirst().orElseThrow().data().path("content").asText())
                .isEqualTo("timed out\n\nbangumi guidance");
        verify(observationService).finishSpanError(
                eq(childSpan), eq("tool_timeout"), anyMap(), anyMap());
        assertThat(objectMapper.valueToTree(trace.toPayloadMap().get("toolCalls"))
                .path(0).path("success").asBoolean()).isFalse();
    }

    @Test
    void failedToolMarksFailedErrorSpanButLoopCanContinue() throws Exception {
        AgentTool tool = tool("search");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"search\",\"input\":{}}")
                .thenReturn("{\"action\":\"final\"}");
        when(toolExecutor.execute(any(), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult("failed", AgentToolResult.Status.ERROR));
        AgentExecutionTrace trace = trace(3);

        AgentLoopResult result = executor.execute(
                request(Map.of(tool.name(), tool), 3), trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        verify(observationService).finishSpanError(
                eq(childSpan), eq("tool_error"), anyMap(), anyMap());
        assertThat(objectMapper.valueToTree(trace.toPayloadMap().get("toolCalls"))
                .path(0).path("success").asBoolean()).isFalse();
    }

    @Test
    void rejectedToolExecutionFinishesStartedSpanBeforePropagating() throws Exception {
        AgentTool tool = tool("search");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"search\",\"input\":{}}");
        when(toolExecutor.execute(any(), anyMap(), anyLong()))
                .thenThrow(new RejectedExecutionException("executor saturated"));
        AgentExecutionTrace trace = trace(3);

        assertThatThrownBy(() -> executor.execute(
                request(Map.of(tool.name(), tool), 3), trace, agentSpan, events::add))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessage("executor saturated");

        verify(observationService).finishSpanError(
                childSpan,
                "tool_executor_error",
                Map.of("status", "error", "error.type", "INTERNAL_ERROR"),
                Map.of());
    }

    private AgentLoopRequest request(Map<String, AgentTool> tools, int maxSteps) {
        return new AgentLoopRequest(
                "system prompt",
                "What happened?",
                42L,
                "Earlier conversation",
                tools,
                maxSteps);
    }

    private AgentExecutionTrace trace(int maxSteps) {
        return new AgentExecutionTrace(42L, "session", maxSteps);
    }

    private List<String> eventTypes() {
        return events.stream().map(AgentEvent::type).toList();
    }

    private AgentTool tool(String name) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn(name);
        return tool;
    }

    private AgentDomainConfig domainConfig() {
        return new AgentDomainConfig(
                new AgentSystemPromptConfig(
                        "fallback",
                        "PARSE_ERROR_ORIGINAL",
                        "JSON parse failed",
                        "final system",
                        "final prompt",
                        "Preparing final answer",
                        "Calling {toolName}",
                        "site guidance",
                        "bangumi guidance",
                        List.of("empty")),
                new AgentErrorMessages(
                        "QUESTION_EMPTY",
                        "MODEL_TIMEOUT",
                        "MODEL_FAILED",
                        "MODEL_INTERRUPTED",
                        "RESPONSE_TIMEOUT",
                        "RESPONSE_FAILED",
                        "Reached max steps: {maxSteps}",
                        "Unknown tool: {toolName}",
                        "Tool failed: {message}",
                        "TOOL_INTERRUPTED",
                        "NO_RESULT"),
                null,
                new AgentContextLabels(
                        "Time:",
                        "User:",
                        "Page:",
                        "Assistant:",
                        "Observation:",
                        "## Current question",
                        "",
                        "",
                        "",
                        "",
                        "",
                        ","));
    }
}
