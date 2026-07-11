package com.chtholly.agent;

import com.chtholly.agent.config.AgentContextLabels;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentErrorMessages;
import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.config.AgentSystemPromptConfig;
import com.chtholly.agent.context.ContextEngine;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentMetrics;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.runtime.AgentLoopExecutor;
import com.chtholly.agent.runtime.AgentLoopRequest;
import com.chtholly.agent.runtime.AgentLoopResult;
import com.chtholly.agent.trace.TracePersistenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChthollyAgentTest {

    @Mock
    private AgentLlmInvoker llmInvoker;
    @Mock
    private AgentLoopExecutor loopExecutor;
    @Mock
    private AgentMetrics agentMetrics;
    @Mock
    private AgentObservationService observationService;
    @Mock
    private Observation agentSpan;
    @Mock
    private Observation llmSpan;
    @Mock
    private ContextEngine contextEngine;
    @Mock
    private TracePersistenceService tracePersistenceService;
    @Mock
    private AgentConversationMemory memory;

    private AgentProperties properties;
    private AgentDomainConfig domainConfig;
    private ChthollyAgent agent;
    private List<AgentEvent> events;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.setMaxSteps(4);
        properties.setStreamCharDelayMs(0);
        properties.setModel("test-model");
        domainConfig = domainConfig();
        when(observationService.startAgentSpan(anyString(), anyLong())).thenReturn(agentSpan);
        agent = new ChthollyAgent(
                llmInvoker,
                loopExecutor,
                properties,
                new ObjectMapper(),
                List.of(tool("search")),
                agentMetrics,
                observationService,
                new CharacterSoulService("soul"),
                contextEngine,
                tracePersistenceService,
                domainConfig);
        events = new ArrayList<>();
    }

    @Test
    void runBuildsContextAndPassesCompleteRequestToLoop() {
        when(memory.formatForPrompt()).thenReturn("history");
        when(contextEngine.buildSystemPrompt(
                anyLong(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("assembled system");
        when(loopExecutor.execute(any(), any(), any(), any()))
                .thenReturn(AgentLoopResult.terminal(
                        AgentLoopResult.Status.MAX_STEPS,
                        List.of("transcript"),
                        "stopped"));

        agent.run("  question  ", 7L, memory, "session", "page", events::add);

        verify(contextEngine).buildSystemPrompt(
                eq(7L), eq("session"), eq("page"), any(), eq("history"), eq("question"));
        ArgumentCaptor<AgentLoopRequest> requestCaptor = ArgumentCaptor.forClass(AgentLoopRequest.class);
        verify(loopExecutor).execute(requestCaptor.capture(), any(), eq(agentSpan), any());
        AgentLoopRequest request = requestCaptor.getValue();
        assertThat(request.systemPrompt()).isEqualTo("assembled system");
        assertThat(request.question()).isEqualTo("question");
        assertThat(request.userId()).isEqualTo(7L);
        assertThat(request.historyBlock()).isEqualTo("history");
        assertThat(request.tools()).containsKey("search");
        assertThat(request.maxSteps()).isEqualTo(4);
    }

    @Test
    void finalReadyStreamsAnswerWritesMemoryAndPersistsSuccessfulTrace() {
        when(memory.formatForPrompt()).thenReturn("history");
        when(contextEngine.buildSystemPrompt(anyLong(), any(), any(), any(), anyString(), anyString()))
                .thenReturn("assembled system");
        when(loopExecutor.execute(any(), any(), any(), any()))
                .thenReturn(AgentLoopResult.finalReady(
                        List.of("history", "current question"),
                        2,
                        123));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(Flux.just("final ", "answer"));

        agent.run("question", 7L, memory, events::add);

        verify(llmInvoker).stream(anyString(), anyString(), eq(0.3), eq(1024));
        ArgumentCaptor<AgentTurn> turnCaptor = ArgumentCaptor.forClass(AgentTurn.class);
        verify(memory, times(2)).add(turnCaptor.capture());
        assertThat(turnCaptor.getAllValues()).extracting(AgentTurn::content)
                .containsExactly("question", "final answer");
        assertThat(eventTypes()).containsExactly("delta", "delta", "final");
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        assertThat(traceCaptor.getValue().getTerminatedBy()).isEqualTo("final_answer");
        assertThat(traceCaptor.getValue().getStatus()).isNotNull();
        com.fasterxml.jackson.databind.JsonNode steps = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap().get("steps"));
        assertThat(steps).hasSize(1);
        assertThat(steps.path(0).path("action").asText()).isEqualTo("final_answer");
        assertThat(steps.path(0).path("stepIndex").asInt()).isEqualTo(2);
        assertThat(steps.path(0).path("llmMs").asLong()).isGreaterThanOrEqualTo(123);
    }

    @Test
    void nonFinalLoopOutcomeDoesNotStreamOrWriteMemoryButStillPersistsTrace() {
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSystemPrompt(anyLong(), any(), any(), any(), anyString(), anyString()))
                .thenReturn("assembled system");
        when(loopExecutor.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            AgentExecutionTrace trace = invocation.getArgument(1);
            trace.terminateMaxSteps();
            trace.setErrorMessage("stopped");
            return AgentLoopResult.terminal(
                    AgentLoopResult.Status.MAX_STEPS,
                    List.of("transcript"),
                    "stopped");
        });

        agent.run("question", 7L, memory, events::add);

        verify(llmInvoker, never()).stream(anyString(), anyString(), anyDouble(), anyInt());
        verify(memory, never()).add(any());
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        assertThat(traceCaptor.getValue().getTerminatedBy()).isEqualTo("max_steps");
    }

    @Test
    void emptyQuestionEmitsErrorWithoutBuildingContextOrEnteringLoopAndPersistsTrace() {
        agent.run("  ", 7L, null, events::add);

        assertThat(eventTypes()).containsExactly("error");
        assertThat(events.getFirst().data().path("message").asText()).isEqualTo("QUESTION_EMPTY");
        verify(contextEngine, never()).buildSystemPrompt(anyLong(), any(), any(), any(), anyString(), anyString());
        verify(loopExecutor, never()).execute(any(), any(), any(), any());
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        assertThat(traceCaptor.getValue().getErrorMessage()).isEqualTo("QUESTION_EMPTY");
    }

    @Test
    void publicOverloadWithoutSessionPassesNullContextParameters() {
        when(contextEngine.buildSystemPrompt(anyLong(), any(), any(), any(), anyString(), anyString()))
                .thenReturn("system");
        when(loopExecutor.execute(any(), any(), any(), any()))
                .thenReturn(AgentLoopResult.terminal(
                        AgentLoopResult.Status.LLM_ERROR,
                        List.of(),
                        "failed"));

        agent.run("question", 7L, null, events::add);

        verify(contextEngine).buildSystemPrompt(
                eq(7L), isNull(), isNull(), any(), eq(""), eq("question"));
    }

    private List<String> eventTypes() {
        return events.stream().map(AgentEvent::type).toList();
    }

    private AgentTool tool(String name) {
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
            public String execute(java.util.Map<String, Object> input, long userId) {
                return "unused";
            }
        };
    }

    private AgentDomainConfig domainConfig() {
        return new AgentDomainConfig(
                new AgentSystemPromptConfig(
                        "fallback",
                        "parse observation",
                        "parse think",
                        "Answer with {soul}",
                        "Produce final answer",
                        "final think",
                        "tool {toolName}",
                        "site guidance",
                        "bangumi guidance",
                        List.of("empty")),
                new AgentErrorMessages(
                        "QUESTION_EMPTY",
                        "MODEL_TIMEOUT",
                        "MODEL_FAILED",
                        "RESPONSE_TIMEOUT",
                        "RESPONSE_FAILED",
                        "MAX {maxSteps}",
                        "UNKNOWN {toolName}",
                        "TOOL_FAILED {message}",
                        "TOOL_INTERRUPTED",
                        "NO_RESULT"),
                null,
                new AgentContextLabels(
                        "time",
                        "User:",
                        "page",
                        "Assistant:",
                        "Observation:",
                        "Current",
                        "",
                        "",
                        "",
                        "",
                        "",
                        ","));
    }
}
