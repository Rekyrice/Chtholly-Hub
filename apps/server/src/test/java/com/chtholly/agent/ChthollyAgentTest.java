package com.chtholly.agent;

import com.chtholly.agent.config.AgentContextLabels;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentErrorMessages;
import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.config.AgentSystemPromptConfig;
import com.chtholly.agent.context.AgentContextSnapshot;
import com.chtholly.agent.context.ContextEngine;
import com.chtholly.agent.evidence.Evidence;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentMetrics;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.runtime.AgentLoopExecutor;
import com.chtholly.agent.runtime.AgentLoopRequest;
import com.chtholly.agent.runtime.AgentLoopResult;
import com.chtholly.agent.runtime.AgentToolPlanner;
import com.chtholly.agent.runtime.AgentTurnControl;
import com.chtholly.agent.skill.SkillDefinition;
import com.chtholly.agent.skill.EvidencePolicy;
import com.chtholly.agent.skill.SkillExecutionContext;
import com.chtholly.agent.skill.SkillOutputValidator;
import com.chtholly.agent.skill.SkillRequestPlanner;
import com.chtholly.agent.skill.SkillRegistry;
import com.chtholly.agent.skill.SkillSelector;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    private Observation skillSpan;
    @Mock
    private Observation retrievalSpan;
    @Mock
    private ContextEngine contextEngine;
    @Mock
    private TracePersistenceService tracePersistenceService;
    @Mock
    private AgentConversationMemory memory;
    @Mock
    private SkillRegistry skillRegistry;
    @Mock
    private SkillSelector skillSelector;

    private AgentProperties properties;
    private AgentDomainConfig domainConfig;
    private ChthollyAgent agent;
    private List<AgentEvent> events;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.setMaxSteps(4);
        properties.setModel("test-model");
        domainConfig = domainConfig();
        when(observationService.startAgentSpan(anyString(), anyLong())).thenReturn(agentSpan);
        org.mockito.Mockito.lenient().when(memory.addExchange(any(), any())).thenReturn(true);
        agent = new ChthollyAgent(
                llmInvoker,
                loopExecutor,
                new AgentToolPlanner(),
                properties,
                new ObjectMapper(),
                List.of(tool("search"), tool("draft_write"), tool("article_rag")),
                agentMetrics,
                observationService,
                new CharacterSoulService("soul"),
                contextEngine,
                tracePersistenceService,
                domainConfig,
                skillRegistry,
                skillSelector,
                new SkillRequestPlanner(),
                new SkillOutputValidator());
        events = new ArrayList<>();
    }

    @Test
    void cancelledTurnStopsBeforeContextAndPersistsCanonicalTurnIdentity() {
        AgentTurnControl control = AgentTurnControl.create(
                "request-cancelled",
                "turn-cancelled",
                "chat-cancelled",
                "connection-cancelled",
                Duration.ofSeconds(30));
        control.cancel();

        agent.run("question", 7L, memory, control, "page", "", events::add);
        control.completeClientDelivery(false, "", "CLIENT_DISCONNECTED");

        verify(contextEngine, never()).buildSnapshot(
                anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean());
        verify(memory, never()).addExchange(any(), any());
        assertThat(events).isEmpty();
        ArgumentCaptor<AgentExecutionTrace> traceCaptor =
                ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        var payload = new ObjectMapper().valueToTree(traceCaptor.getValue().toPayloadMap());
        assertThat(payload.path("terminatedBy").asText()).isEqualTo("cancelled");
        assertThat(payload.path("failureType").asText()).isEqualTo("TURN_CANCELLED");
        assertThat(payload.path("turn").path("requestId").asText()).isEqualTo("request-cancelled");
        assertThat(payload.path("turn").path("turnId").asText()).isEqualTo("turn-cancelled");
        assertThat(payload.path("turn").path("cancelled").asBoolean()).isTrue();
        assertThat(payload.path("turn").path("timeoutStage").asText()).isEmpty();
    }

    @Test
    void expiredTurnStopsBeforeContextAndRecordsTimeoutStage() throws Exception {
        AgentTurnControl control = AgentTurnControl.create(
                "request-timeout",
                "turn-timeout",
                "chat-timeout",
                "connection-timeout",
                Duration.ofNanos(1));
        Thread.sleep(1);

        agent.run("question", 7L, memory, control, "page", "", events::add);
        control.completeClientDelivery(true, "error", "TURN_TIMEOUT");

        verify(contextEngine, never()).buildSnapshot(
                anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean());
        verify(memory, never()).addExchange(any(), any());
        assertThat(eventTypes()).containsExactly("error");
        ArgumentCaptor<AgentExecutionTrace> traceCaptor =
                ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        var payload = new ObjectMapper().valueToTree(traceCaptor.getValue().toPayloadMap());
        assertThat(payload.path("terminatedBy").asText()).isEqualTo("timeout");
        assertThat(payload.path("failureType").asText()).isEqualTo("TURN_TIMEOUT");
        assertThat(payload.path("turn").path("timeoutStage").asText()).isEqualTo("turn_start");
    }

    @Test
    void websocketTraceFinalizationWaitsForClientTerminalOutcome() {
        AgentTurnControl control = AgentTurnControl.create(
                "request-delivery",
                "turn-delivery",
                "chat-delivery",
                "connection-delivery",
                Duration.ofSeconds(30));

        agent.run(" ", 7L, null, control, "", "", events::add);

        verify(tracePersistenceService, never()).persist(any());

        control.completeClientDelivery(true, "error", "QUESTION_EMPTY");

        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        assertThat(traceCaptor.getValue().toPayloadMap())
                .extracting(payload -> ((Map<?, ?>) payload.get("turn")).get("clientDeliveryStatus"))
                .isEqualTo("DELIVERED");
    }

    @Test
    void retrievalConsumesOnlyTheRemainingWholeTurnBudgetAndIsInterrupted() throws Exception {
        CountDownLatch retrievalStarted = new CountDownLatch(1);
        CountDownLatch retrievalInterrupted = new CountDownLatch(1);
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(
                anyLong(), anyString(), anyString(), any(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    retrievalStarted.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException exception) {
                        retrievalInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return snapshot("too late");
                });
        AgentTurnControl control = AgentTurnControl.create(
                "request-retrieval-timeout",
                "turn-retrieval-timeout",
                "chat-retrieval-timeout",
                "connection-retrieval-timeout",
                Duration.ofMillis(150));

        agent.run("question", 7L, memory, control, "page", "", events::add);
        control.completeClientDelivery(true, "error", "TURN_TIMEOUT");

        assertThat(retrievalStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(retrievalInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        verify(loopExecutor, never()).execute(any(), any(), any(), any());
        verify(memory, never()).addExchange(any(), any());
        assertThat(eventTypes()).containsExactly("error");
        ArgumentCaptor<AgentExecutionTrace> traceCaptor =
                ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        var payload = new ObjectMapper().valueToTree(traceCaptor.getValue().toPayloadMap());
        assertThat(payload.path("terminatedBy").asText()).isEqualTo("timeout");
        assertThat(payload.path("turn").path("timeoutStage").asText()).isEqualTo("retrieval");
    }

    @Test
    void runBuildsContextAndPassesCompleteRequestToLoop() {
        when(memory.formatForPrompt()).thenReturn("history");
        when(contextEngine.buildSnapshot(
                anyLong(), anyString(), anyString(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(snapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any()))
                .thenReturn(AgentLoopResult.terminal(
                        AgentLoopResult.Status.MAX_STEPS,
                        List.of("transcript"),
                        "stopped"));

        agent.run("  question  ", 7L, memory, "session", "page", events::add);

        verify(contextEngine).buildSnapshot(
                eq(7L), eq("session"), eq("page"), any(), eq("history"), eq("question"), eq(false));
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
    void selectedReadOnlySkillReceivesPageContextAndNarrowsRuntimeTools() {
        SkillDefinition definition = skillDefinition();
        when(memory.formatForPrompt()).thenReturn("");
        when(skillRegistry.enabled()).thenReturn(List.of(definition));
        when(skillSelector.select(any(), any())).thenReturn(new SkillSelector.SkillSelection(
                SkillSelector.Status.SELECTED,
                definition,
                "explicit_task_type",
                1.0,
                Set.of("search", "article_rag")));
        when(contextEngine.buildSnapshot(
                anyLong(), anyString(), anyString(), any(), anyString(), anyString(),
                eq(EvidencePolicy.REQUIRED), eq("文章详情")))
                .thenReturn(groundedSnapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any()))
                .thenReturn(AgentLoopResult.terminal(
                        AgentLoopResult.Status.MAX_STEPS, List.of(), "stopped"));

        agent.run(
                "解释这个页面",
                7L,
                memory,
                "session",
                "页面：文章详情",
                "page-explain",
                events::add);

        ArgumentCaptor<SkillExecutionContext> contextCaptor =
                ArgumentCaptor.forClass(SkillExecutionContext.class);
        verify(skillSelector).select(eq(List.of(definition)), contextCaptor.capture());
        assertThat(contextCaptor.getValue().taskType()).isEqualTo("page-explain");
        assertThat(contextCaptor.getValue().pageContext()).isEqualTo("页面：文章详情");
        ArgumentCaptor<AgentLoopRequest> requestCaptor = ArgumentCaptor.forClass(AgentLoopRequest.class);
        verify(loopExecutor).execute(requestCaptor.capture(), any(), eq(agentSpan), any());
        assertThat(requestCaptor.getValue().tools()).containsOnlyKeys("search");
        assertThat(requestCaptor.getValue().systemPrompt())
                .contains("skillId=page-explain", "skillVersion=v1", "只读合同", "allowedTools=search")
                .doesNotContain("allowedTools=article_rag");
        assertThat(requestCaptor.getValue().maxSteps()).isEqualTo(3);
        assertThat(requestCaptor.getValue().turnBudget()).isNotNull();
        assertThat(requestCaptor.getValue().turnBudget().totalBudget())
                .isEqualTo(Duration.ofSeconds(30));
        ArgumentCaptor<AgentExecutionTrace> traceCaptor =
                ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        var payload = new ObjectMapper().valueToTree(traceCaptor.getValue().toPayloadMap());
        assertThat(payload.path("turn").path("budgetMs").asLong()).isEqualTo(30_000L);
        assertThat(payload.path("toolPlan").path("reason").asText())
                .isEqualTo("selected_skill_evidence_only");
        assertThat(payload.path("toolPlan").path("effectiveTools").get(0).asText())
                .isEqualTo("search");
    }

    @Test
    void finalReadyStreamsAnswerWritesMemoryAndPersistsSuccessfulTrace() {
        properties.setMaxResponseChars(5);
        when(memory.formatForPrompt()).thenReturn("history");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(snapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(80);
                    return AgentLoopResult.finalReady(
                            List.of("history", "current question"),
                            2,
                            123);
                });
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just("final ", "answer"));
        List<String> lifecycle = new ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            AgentTurn userTurn = invocation.getArgument(0);
            AgentTurn assistantTurn = invocation.getArgument(1);
            lifecycle.add("memory:" + userTurn.role().name().toLowerCase());
            lifecycle.add("memory:" + assistantTurn.role().name().toLowerCase());
            return true;
        }).when(memory).addExchange(any(), any());

        agent.run("question", 7L, memory, event -> {
            events.add(event);
            lifecycle.add("event:" + event.type());
        });

        verify(llmInvoker).stream(
                anyString(), anyString(), eq(0.3), eq(1024), any(Duration.class));
        ArgumentCaptor<AgentTurn> userTurnCaptor = ArgumentCaptor.forClass(AgentTurn.class);
        ArgumentCaptor<AgentTurn> assistantTurnCaptor = ArgumentCaptor.forClass(AgentTurn.class);
        verify(memory).addExchange(userTurnCaptor.capture(), assistantTurnCaptor.capture());
        assertThat(List.of(userTurnCaptor.getValue(), assistantTurnCaptor.getValue()))
                .extracting(AgentTurn::content)
                .containsExactly("question", "final");
        assertThat(eventTypes()).containsExactly("delta", "final");
        assertThat(lifecycle).containsExactly(
                "memory:user", "memory:assistant", "event:delta", "event:final");
        ArgumentCaptor<String> finalSystemCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvoker).stream(
                finalSystemCaptor.capture(), anyString(), eq(0.3), eq(1024), any(Duration.class));
        assertThat(finalSystemCaptor.getValue()).contains("assembled system", "Answer with soul");
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        assertThat(traceCaptor.getValue().getTerminatedBy()).isEqualTo("final_answer");
        assertThat(traceCaptor.getValue().getStatus()).isNotNull();
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap());
        com.fasterxml.jackson.databind.JsonNode steps = payload.path("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.path(0).path("action").asText()).isEqualTo("final_answer");
        assertThat(steps.path(0).path("stepIndex").asInt()).isEqualTo(2);
        assertThat(steps.path(0).path("llmMs").asLong()).isGreaterThanOrEqualTo(123);
        com.fasterxml.jackson.databind.JsonNode finalCall = payload.path("llmCalls").path(0);
        assertThat(finalCall.path("purpose").asText()).isEqualTo("FINAL_ANSWER");
        assertThat(finalCall.path("model").asText()).isEqualTo("test-model");
        assertThat(finalCall.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(finalCall.path("attempt").asInt()).isEqualTo(1);
        assertThat(finalCall.path("output_chars").asInt()).isEqualTo("final answer".length());
        assertThat(finalCall.path("budget_before_ms").asLong())
                .isGreaterThanOrEqualTo(finalCall.path("budget_after_ms").asLong());
        assertThat(finalCall.path("first_token_ms").asLong())
                .isLessThanOrEqualTo(finalCall.path("duration_ms").asLong() + 25L);
        assertThat(payload.path("answerTiming").path("modelFirstTokenMs").asLong())
                .isGreaterThan(finalCall.path("first_token_ms").asLong());
    }

    @Test
    void memoryWriteCannotCrossTheTurnDeadlineAndLeakAVisibleAnswer() {
        when(memory.formatForPrompt()).thenReturn("history");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(snapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any()))
                .thenReturn(AgentLoopResult.finalReady(List.of("current question"), 1, 10));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just("answer"));
        org.mockito.Mockito.doAnswer(invocation -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new AgentMemoryStore.MemoryWriteResult(
                    AgentMemoryStore.MemoryWriteStatus.REJECTED,
                    "CALL_CANCELLED");
        }).when(memory).addExchange(any(), any(), any(AgentTurnControl.class), anyLong());
        AgentTurnControl control = AgentTurnControl.create(
                "request-memory-timeout",
                "turn-memory-timeout",
                "session-memory-timeout",
                "connection-memory-timeout",
                Duration.ofMillis(50));

        agent.run("question", 7L, memory, control, "", "", events::add);
        control.completeClientDelivery(true, "error", "TURN_TIMEOUT");

        assertThat(eventTypes()).containsExactly("error");
        assertThat(eventContents()).noneMatch(content -> content.contains("answer"));
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        assertThat(traceCaptor.getValue().getTimeoutStage()).isEqualTo("memory_write");
        assertThat(traceCaptor.getValue().getFailureType())
                .isEqualTo(AgentExecutionTrace.FailureType.TURN_TIMEOUT);
    }

    @Test
    void rejectedMemoryWriteDoesNotEmitAVisibleAnswerOrSuccessfulTrace() {
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(snapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any()))
                .thenReturn(AgentLoopResult.finalReady(List.of("current question"), 1, 10));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just("answer"));
        org.mockito.Mockito.doReturn(false).when(memory).addExchange(any(), any());

        assertThatThrownBy(() -> agent.run("question", 7L, memory, events::add))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("STORE_REJECTED");

        assertThat(eventTypes()).isEmpty();
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        assertThat(traceCaptor.getValue().getFailureType())
                .isEqualTo(AgentExecutionTrace.FailureType.MEMORY_WRITE_FAILED);
        assertThat(traceCaptor.getValue().getStatus())
                .isEqualTo(com.chtholly.agent.trace.TraceStatus.FAILURE);
    }

    @Test
    void answerSinkFailureDoesNotFinishSuccessfulLlmSpanTwice() {
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(snapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any()))
                .thenReturn(AgentLoopResult.finalReady(List.of("current question"), 1, 10));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just("final answer"));

        assertThatThrownBy(() -> agent.run(
                "question",
                7L,
                memory,
                event -> {
                    throw new IllegalStateException("sink closed");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("sink closed");

        verify(observationService, times(1)).finishSpan(eq(llmSpan), anyMap(), eq(Map.of()));
        verify(observationService, never()).finishSpanError(eq(llmSpan), anyString(), anyMap(), anyMap());
    }

    @Test
    void nonFinalLoopOutcomeDoesNotStreamOrWriteMemoryButStillPersistsTrace() {
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(snapshot("assembled system"));
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

        verify(llmInvoker, never()).stream(
                anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class));
        verify(memory, never()).addExchange(any(), any());
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        assertThat(traceCaptor.getValue().getTerminatedBy()).isEqualTo("max_steps");
    }

    @Test
    void emptyQuestionEmitsErrorWithoutBuildingContextOrEnteringLoopAndPersistsTrace() {
        agent.run("  ", 7L, null, events::add);

        assertThat(eventTypes()).containsExactly("error");
        assertThat(events.getFirst().data().path("message").asText()).isEqualTo("QUESTION_EMPTY");
        verify(contextEngine, never()).buildSnapshot(
                anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean());
        verify(loopExecutor, never()).execute(any(), any(), any(), any());
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        assertThat(traceCaptor.getValue().getErrorMessage()).isEqualTo("QUESTION_EMPTY");
        assertThat(traceCaptor.getValue().getFailureType().name()).isEqualTo("INVALID_INPUT");
    }

    @Test
    void publicOverloadWithoutSessionPassesNullContextParameters() {
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(snapshot("system"));
        when(loopExecutor.execute(any(), any(), any(), any()))
                .thenReturn(AgentLoopResult.terminal(
                        AgentLoopResult.Status.LLM_ERROR,
                        List.of(),
                        "failed"));

        agent.run("question", 7L, null, events::add);

        verify(contextEngine).buildSnapshot(
                eq(7L), isNull(), isNull(), any(), eq(""), eq("question"), eq(false));
    }

    @Test
    void agentSpanDoesNotDuplicateNativeDurationButTraceKeepsIt() {
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(snapshot("system"));
        when(loopExecutor.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(20);
            AgentExecutionTrace trace = invocation.getArgument(1);
            trace.terminateMaxSteps();
            trace.setErrorMessage("stopped");
            return AgentLoopResult.terminal(
                    AgentLoopResult.Status.MAX_STEPS,
                    List.of("transcript"),
                    "stopped");
        });

        agent.run("question", 7L, null, events::add);

        ArgumentCaptor<Map<String, String>> attributesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(observationService).finishSpan(
                eq(agentSpan), attributesCaptor.capture(), eq(Map.of()));
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        assertThat(attributesCaptor.getValue())
                .containsEntry("status", "max_steps")
                .doesNotContainKey("agent.duration_ms");
        assertThat(traceCaptor.getValue().getDurationMs()).isGreaterThanOrEqualTo(15);
    }

    @Test
    void finalGenerationReceivesImmutableSkillAndEvidenceSystem() {
        SkillDefinition definition = skillDefinition();
        when(observationService.startSkillSpan(agentSpan)).thenReturn(skillSpan);
        when(observationService.startRetrievalSpan(agentSpan, "document-rrf-v1"))
                .thenReturn(retrievalSpan);
        when(memory.formatForPrompt()).thenReturn("");
        when(skillRegistry.enabled()).thenReturn(List.of(definition));
        when(skillSelector.select(any(), any())).thenReturn(new SkillSelector.SkillSelection(
                SkillSelector.Status.SELECTED,
                definition,
                "explicit_task_type",
                1.0,
                Set.of("search")));
        when(contextEngine.buildSnapshot(
                anyLong(), any(), any(), any(), anyString(), anyString(),
                eq(EvidencePolicy.REQUIRED), eq("页面")))
                .thenReturn(groundedSnapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any())).thenReturn(
                AgentLoopResult.finalReady(List.of("current question"), 1, 10));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just("有依据 [E1]"));

        agent.run("解释这个页面", 7L, memory, "session", "页面", "page-explain", events::add);

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvoker).stream(
                systemCaptor.capture(), anyString(), eq(0.3), eq(1024), any(Duration.class));
        assertThat(systemCaptor.getValue())
                .contains("assembled system")
                .contains("skillId=page-explain", "skillVersion=v1", "只读合同")
                .contains("<evidence_data>证据内容</evidence_data>")
                .contains("Answer with soul");
        assertThat(eventContents()).containsExactly("有依据 [E1]", "有依据 [E1]");
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap());
        assertThat(payload.path("skill").path("id").asText()).isEqualTo("page-explain");
        assertThat(payload.path("skill").path("version").asText()).isEqualTo("v1");
        assertThat(payload.path("skill").path("validationStatus").asText()).isEqualTo("VALID");
        assertThat(payload.path("retrieval").path("statuses").path("semantic").asText())
                .isEqualTo("SUCCESS_RESULTS");
        assertThat(payload.path("retrieval").path("evidenceCount").asInt()).isEqualTo(1);
        assertThat(payload.path("retrieval").path("citationValidationStatus").asText())
                .isEqualTo("VALID");
        assertThat(payload.path("components").path("model").asText()).isEqualTo("test-model");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> skillLow = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> skillHigh = ArgumentCaptor.forClass(Map.class);
        verify(observationService).finishSpan(eq(skillSpan), skillLow.capture(), skillHigh.capture());
        assertThat(skillLow.getValue())
                .containsEntry("skill.id", "page-explain")
                .containsEntry("skill.version", "v1")
                .containsEntry("status", "valid");
        assertThat(skillHigh.getValue())
                .containsEntry("skill.selection.status", "SELECTED")
                .containsEntry("skill.validation.status", "VALID");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> retrievalLow = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> retrievalHigh = ArgumentCaptor.forClass(Map.class);
        verify(observationService).finishSpan(
                eq(retrievalSpan), retrievalLow.capture(), retrievalHigh.capture());
        assertThat(retrievalLow.getValue()).containsEntry("status", "success");
        assertThat(retrievalHigh.getValue())
                .containsEntry("retrieval.semantic.status", "SUCCESS_RESULTS")
                .containsEntry("retrieval.evidence_count", "1")
                .containsEntry("retrieval.citation_validation", "VALID");
    }

    @Test
    void forgedCitationNeverReachesDeltaMemoryOrTrace() {
        when(observationService.startRetrievalSpan(agentSpan, "document-rrf-v1"))
                .thenReturn(retrievalSpan);
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(groundedSnapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any())).thenReturn(
                AgentLoopResult.finalReady(List.of("current question"), 1, 10));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(
                        Flux.just("伪造事实 [E999]"),
                        Flux.just("这次的引用和资料对不上，我不能把它当成可靠答案。"));

        agent.run("帮我查站内事实", 7L, memory, events::add);

        assertThat(eventTypes()).containsExactly("delta", "final");
        String safeAnswer = "这次的引用和资料对不上，我不能把它当成可靠答案。";
        assertThat(eventContents())
                .containsOnly(safeAnswer)
                .noneMatch(content -> content.contains("E999") || content.contains("伪造"));
        ArgumentCaptor<AgentTurn> assistantTurnCaptor = ArgumentCaptor.forClass(AgentTurn.class);
        verify(memory).addExchange(any(), assistantTurnCaptor.capture());
        assertThat(assistantTurnCaptor.getValue().content())
                .isEqualTo(safeAnswer);
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        assertThat(traceCaptor.getValue().getFinalAnswerLength())
                .isEqualTo(safeAnswer.length());
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap());
        assertThat(payload.path("retrieval").path("citationValidationStatus").asText())
                .isEqualTo("UNKNOWN_CITATION");
        assertThat(payload.path("failureType").asText()).isEqualTo("CITATION_INVALID");
        assertThat(payload.path("outcomeReason").asText()).isEqualTo("INVALID_CITATION");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> retrievalLow = ArgumentCaptor.forClass(Map.class);
        verify(observationService).finishSpanError(
                eq(retrievalSpan), eq("retrieval_failed"), retrievalLow.capture(), anyMap());
        assertThat(retrievalLow.getValue())
                .containsEntry("status", "error")
                .containsEntry("error.type", "CITATION_INVALID");
    }

    @Test
    void missingCitationIsRepairedOnceBeforeAnyAnswerEvent() throws Exception {
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(groundedSnapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any())).thenReturn(
                AgentLoopResult.finalReady(List.of("current question"), 1, 10));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just("没有引用的站内事实"));
        when(llmInvoker.call(anyString(), anyString(), eq(0.0), eq(1024)))
                .thenReturn("没有引用的站内事实 [E1]");

        agent.run("帮我查站内事实", 7L, memory, events::add);

        assertThat(eventContents()).containsOnly("没有引用的站内事实 [E1]");
        verify(llmInvoker, times(1)).call(anyString(), anyString(), eq(0.0), eq(1024));
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode calls = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap().get("llmCalls"));
        assertThat(calls).hasSize(2);
        assertThat(calls).extracting(call -> call.path("purpose").asText())
                .containsExactly("FINAL_ANSWER", "CITATION_REPAIR");
        assertThat(calls).extracting(call -> call.path("status").asText())
                .containsOnly("SUCCESS");
    }

    @Test
    void failedBoundaryModelCallRecordsErrorWithoutCountingFallbackAsModelOutput() {
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new AgentContextSnapshot("assembled system", EvidenceSet.empty(), true));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.error(new IllegalStateException("provider failed")));

        agent.run("question", 7L, memory, events::add);

        assertThat(eventTypes()).containsExactly("delta", "final");
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode call = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap().get("llmCalls")).path(0);
        assertThat(call.path("purpose").asText()).isEqualTo("BOUNDARY_RESPONSE");
        assertThat(call.path("status").asText()).isEqualTo("ERROR");
        assertThat(call.path("error_code").asText()).isEqualTo("LLM_ERROR");
        assertThat(call.path("output_chars").asInt()).isZero();
    }

    @Test
    void partiallyFailedBoundaryModelCallKeepsStepIndexAndCountsRawOutput() {
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new AgentContextSnapshot("assembled system", EvidenceSet.empty(), true));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.concat(
                        Flux.just("partial"),
                        Flux.error(new TimeoutException("provider timeout"))));

        agent.run("question", 7L, memory, events::add);

        assertThat(eventTypes()).containsExactly("delta", "final");
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode call = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap().get("llmCalls")).path(0);
        assertThat(call.path("step_index").asInt()).isZero();
        assertThat(call.path("purpose").asText()).isEqualTo("BOUNDARY_RESPONSE");
        assertThat(call.path("status").asText()).isEqualTo("TIMEOUT");
        assertThat(call.path("error_code").asText()).isEqualTo("LLM_TIMEOUT");
        assertThat(call.path("output_chars").asInt()).isEqualTo("partial".length());
        assertThat(call.path("first_token_ms").asLong()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void boundaryTraceCountsRawModelOutputBeforeVisibleTruncation() {
        String modelOutput = "暂时没有足够资料" + "补".repeat(500);
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new AgentContextSnapshot("assembled system", EvidenceSet.empty(), true));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just(modelOutput));

        agent.run("question", 7L, memory, events::add);

        assertThat(eventContents().getFirst()).hasSize(400);
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode call = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap().get("llmCalls")).path(0);
        assertThat(call.path("purpose").asText()).isEqualTo("BOUNDARY_RESPONSE");
        assertThat(call.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(call.path("output_chars").asInt()).isEqualTo(modelOutput.length());
    }

    @Test
    void timedOutFinalModelCallRecordsStructuredTimeout() {
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(snapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any()))
                .thenReturn(AgentLoopResult.finalReady(List.of("current question"), 1, 10));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.concat(
                        Flux.just("partial"),
                        Flux.error(new TimeoutException("provider timeout"))));

        agent.run("question", 7L, memory, events::add);

        assertThat(eventTypes()).containsExactly("error");
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode call = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap().get("llmCalls")).path(0);
        assertThat(call.path("purpose").asText()).isEqualTo("FINAL_ANSWER");
        assertThat(call.path("status").asText()).isEqualTo("TIMEOUT");
        assertThat(call.path("error_code").asText()).isEqualTo("LLM_TIMEOUT");
        assertThat(call.path("output_chars").asInt()).isEqualTo("partial".length());
        assertThat(call.path("first_token_ms").asLong()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void requiredEvidenceEmptyShortCircuitsLoopAndLlm() {
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new AgentContextSnapshot("assembled system", EvidenceSet.empty(), true));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just("我认真找过了，但站内暂时没有足够资料支撑这次回答。"));

        agent.run("帮我查站内事实", 7L, memory, events::add);

        verify(loopExecutor, never()).execute(any(), any(), any(), any());
        assertThat(eventTypes()).containsExactly("delta", "final");
        String safeAnswer = "我认真找过了，但站内暂时没有足够资料支撑这次回答。";
        assertThat(eventContents()).containsOnly(safeAnswer);
        ArgumentCaptor<AgentTurn> userTurnCaptor = ArgumentCaptor.forClass(AgentTurn.class);
        ArgumentCaptor<AgentTurn> assistantTurnCaptor = ArgumentCaptor.forClass(AgentTurn.class);
        verify(memory).addExchange(userTurnCaptor.capture(), assistantTurnCaptor.capture());
        assertThat(List.of(userTurnCaptor.getValue(), assistantTurnCaptor.getValue()))
                .extracting(AgentTurn::content)
                .containsExactly("帮我查站内事实", safeAnswer);
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap());
        assertThat(payload.path("retrieval").path("citationValidationStatus").asText())
                .isEqualTo("NO_EVIDENCE");
        assertThat(payload.path("failureType").asText()).isEqualTo("RETRIEVAL_EMPTY");
        assertThat(payload.path("outcomeReason").asText()).isEqualTo("NO_EVIDENCE");
    }

    @Test
    void missingOutlineTopicClarifiesWithoutBuildingContextOrRunningRetrieval() {
        SkillDefinition definition = evidenceOutlineDefinition();
        select(definition);
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just("嗯，可以呀。不过你还没告诉我想写什么主题。"));

        agent.run(
                "根据站内资料生成一份文章大纲",
                7L,
                memory,
                "session",
                "",
                "evidence-outline",
                events::add);

        verify(contextEngine, never()).buildSnapshot(
                anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean());
        verify(contextEngine, never()).buildSnapshot(
                anyLong(), any(), any(), any(), anyString(), anyString(), any(), anyString());
        verify(loopExecutor, never()).execute(any(), any(), any(), any());
        ArgumentCaptor<String> soulPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmInvoker).stream(
                soulPrompt.capture(), anyString(), anyDouble(), anyInt(), any(Duration.class));
        assertThat(soulPrompt.getValue()).contains("soul", "NEEDS_CLARIFICATION");
        assertThat(eventContents()).containsOnly("嗯，可以呀。不过你还没告诉我想写什么主题。");
        assertOutcomeReason("NEEDS_CLARIFICATION");
    }

    @Test
    void generalOutlineUsesOptionalEvidenceAndCanGenerateWithoutCitations() {
        SkillDefinition definition = evidenceOutlineDefinition();
        select(definition);
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(
                anyLong(),
                anyString(),
                anyString(),
                any(),
                anyString(),
                anyString(),
                eq(EvidencePolicy.OPTIONAL),
                eq("Redis 缓存一致性")))
                .thenReturn(snapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any())).thenReturn(
                AgentLoopResult.finalReady(List.of("current question"), 1, 10));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just("# 一致性问题\n\n通用背景。\n\n## 解决路径\n\n通用方案。"));

        agent.run(
                "给我列一个关于 Redis 缓存一致性的技术分享提纲",
                7L,
                memory,
                "session",
                "",
                "evidence-outline",
                events::add);

        verify(loopExecutor).execute(any(), any(), any(), any());
        assertThat(eventContents().getLast())
                .contains("# 一致性问题", "## 解决路径")
                .doesNotContain("[E");
    }

    @Test
    void groundedOutlineWithoutEvidenceUsesPersonaResponseAndNoEvidenceTraceReason() {
        SkillDefinition definition = evidenceOutlineDefinition();
        select(definition);
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(
                anyLong(),
                anyString(),
                anyString(),
                any(),
                anyString(),
                anyString(),
                eq(EvidencePolicy.REQUIRED),
                eq("Redis 缓存一致性")))
                .thenReturn(new AgentContextSnapshot("assembled system", EvidenceSet.empty(), true));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just("我认真找过了，但站内暂时没有足够资料支撑这份大纲。"));

        agent.run(
                "根据站内资料，生成一份关于 Redis 缓存一致性的文章大纲。",
                7L,
                memory,
                "session",
                "",
                "evidence-outline",
                events::add);

        verify(loopExecutor, never()).execute(any(), any(), any(), any());
        assertThat(eventContents())
                .containsOnly("我认真找过了，但站内暂时没有足够资料支撑这份大纲。");
        assertOutcomeReason("NO_EVIDENCE");
    }

    @Test
    void invalidCitationUsesIndependentTraceReasonAndPersonaResponse() {
        SkillDefinition definition = skillDefinition();
        select(definition);
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(
                anyLong(),
                anyString(),
                anyString(),
                any(),
                anyString(),
                anyString(),
                eq(EvidencePolicy.REQUIRED),
                anyString()))
                .thenReturn(groundedSnapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any())).thenReturn(
                AgentLoopResult.finalReady(List.of("current question"), 1, 10));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(
                        Flux.just("伪造引用 [E9]"),
                        Flux.just("这次的引用对不上，我不能把它当成可靠答案。"));

        agent.run(
                "解释这篇文章",
                7L,
                memory,
                "session",
                "标题：文章\npostSlug：article",
                "page-explain",
                events::add);

        assertThat(eventContents()).containsOnly("这次的引用对不上，我不能把它当成可靠答案。");
        assertOutcomeReason("INVALID_CITATION");
    }

    @Test
    void requiredEvidenceTimeoutKeepsTimeoutFailureClassification() {
        when(observationService.startRetrievalSpan(agentSpan, "document-rrf-v1"))
                .thenReturn(retrievalSpan);
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new AgentContextSnapshot(
                        "assembled system",
                        EvidenceSet.empty(),
                        true,
                        Map.of(
                                "semantic", "TIMEOUT",
                                "keyword", "SUCCESS_EMPTY",
                                "entity", "SUCCESS_EMPTY")));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just("我认真找过了，但站内暂时没有足够资料支撑这次回答。"));

        agent.run("帮我查站内事实", 7L, memory, events::add);

        verify(loopExecutor, never()).execute(any(), any(), any(), any());
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap());
        assertThat(payload.path("failureType").asText()).isEqualTo("RETRIEVAL_TIMEOUT");
        assertThat(payload.path("retrieval").path("degraded").asBoolean()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> retrievalLow = ArgumentCaptor.forClass(Map.class);
        verify(observationService).finishSpanError(
                eq(retrievalSpan), eq("retrieval_failed"), retrievalLow.capture(), anyMap());
        assertThat(retrievalLow.getValue()).containsEntry("error.type", "RETRIEVAL_TIMEOUT");
    }

    @Test
    void invalidSkillOutputIsBufferedAndClassifiedBeforeAnyAnswerEvent() {
        SkillDefinition definition = evidenceOutlineDefinition();
        when(observationService.startSkillSpan(agentSpan)).thenReturn(skillSpan);
        when(memory.formatForPrompt()).thenReturn("");
        when(skillRegistry.enabled()).thenReturn(List.of(definition));
        when(skillSelector.select(any(), any())).thenReturn(new SkillSelector.SkillSelection(
                SkillSelector.Status.SELECTED,
                definition,
                "explicit_task_type",
                1.0,
                Set.of("search")));
        when(contextEngine.buildSnapshot(
                anyLong(), any(), any(), any(), anyString(), anyString(),
                eq(EvidencePolicy.REQUIRED), eq("证据")))
                .thenReturn(groundedSnapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any())).thenReturn(
                AgentLoopResult.finalReady(List.of("current question"), 1, 10));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just("只有一行 [E1]"));

        agent.run("生成证据大纲", 7L, memory, "session", "页面", "evidence-outline", events::add);

        String safeOutput = new SkillOutputValidator()
                .validate(definition, "只有一行 [E1]", evidence(), "生成证据大纲")
                .output();
        assertThat(eventTypes()).containsExactly("delta", "final");
        assertThat(eventContents()).containsOnly(safeOutput).noneMatch(content -> content.contains("只有一行"));
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap());
        assertThat(payload.path("skill").path("validationStatus").asText()).isEqualTo("SCHEMA_INVALID");
        assertThat(payload.path("retrieval").path("citationValidationStatus").asText()).isEqualTo("VALID");
        assertThat(payload.path("failureType").asText()).isEqualTo("SKILL_VALIDATION_FAILED");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> skillLow = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> skillHigh = ArgumentCaptor.forClass(Map.class);
        verify(observationService).finishSpanError(
                eq(skillSpan), eq("skill_failed"), skillLow.capture(), skillHigh.capture());
        assertThat(skillLow.getValue()).containsEntry("error.type", "SKILL_VALIDATION_FAILED");
        assertThat(skillHigh.getValue()).containsEntry("skill.validation.status", "SCHEMA_INVALID");
    }

    @Test
    void clarificationRequiredIsClassifiedWithoutEnteringContextOrLoop() {
        when(skillRegistry.enabled()).thenReturn(List.of());
        when(skillSelector.select(any(), any())).thenReturn(new SkillSelector.SkillSelection(
                SkillSelector.Status.CLARIFICATION_REQUIRED,
                null,
                "unknown_or_ambiguous_task_type",
                0.0,
                Set.of()));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn(Flux.just("先告诉我想使用哪一种任务，或者直接说说想完成什么吧。"));

        agent.run("执行未知任务", 7L, memory, "session", "页面", "unknown", events::add);

        verify(contextEngine, never()).buildSnapshot(
                anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean());
        verify(loopExecutor, never()).execute(any(), any(), any(), any());
        assertThat(eventTypes()).containsExactly("delta", "final");
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap());
        assertThat(payload.path("skill").path("selectionStatus").asText())
                .isEqualTo("CLARIFICATION_REQUIRED");
        assertThat(payload.path("failureType").asText()).isEqualTo("SKILL_NO_MATCH");
        assertThat(payload.path("outcomeReason").asText()).isEqualTo("NEEDS_CLARIFICATION");
    }

    private List<String> eventTypes() {
        return events.stream().map(AgentEvent::type).toList();
    }

    private List<String> eventContents() {
        return events.stream().map(event -> event.data().path("content").asText()).toList();
    }

    private void assertOutcomeReason(String expected) {
        ArgumentCaptor<AgentExecutionTrace> traceCaptor =
                ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap());
        assertThat(payload.path("outcomeReason").asText()).isEqualTo(expected);
    }

    private void select(SkillDefinition definition) {
        when(skillRegistry.enabled()).thenReturn(List.of(definition));
        when(skillSelector.select(any(), any())).thenReturn(new SkillSelector.SkillSelection(
                SkillSelector.Status.SELECTED,
                definition,
                "explicit_task_type",
                1.0,
                Set.of("search")));
    }

    private AgentContextSnapshot snapshot(String systemPrompt) {
        return new AgentContextSnapshot(systemPrompt, EvidenceSet.empty(), false);
    }

    private AgentContextSnapshot groundedSnapshot(String systemPrompt) {
        EvidenceSet evidence = evidence();
        return new AgentContextSnapshot(
                systemPrompt + "\n\n" + evidence.renderForPrompt(), evidence, true, Map.of(
                        "semantic", "SUCCESS_RESULTS",
                        "keyword", "SUCCESS_RESULTS",
                        "entity", "SUCCESS_EMPTY"));
    }

    private EvidenceSet evidence() {
        Evidence item = new Evidence(
                "ev-1", "POST", "post:1", "post:1", "post:1#0",
                "文章标题", "semantic+keyword", "v1", "hash-1", "证据内容",
                1, 0.9, Set.of("PUBLIC"), "E1");
        return EvidenceSet.of(List.of(item), Set.of("PUBLIC"));
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

    private SkillDefinition skillDefinition() {
        return new SkillDefinition(
                "page-explain",
                "v1",
                true,
                "test skill",
                List.of("page_explain"),
                List.of("QUESTION", "PAGE"),
                List.of("search"),
                "只读合同",
                Map.of("question", "string"),
                Map.of("type", "PAGE_EXPLAIN", "requiresEvidence", true),
                List.of("citation"),
                "READ_ONLY",
                "NONE",
                30_000,
                3,
                "test-v1");
    }

    private SkillDefinition evidenceOutlineDefinition() {
        return new SkillDefinition(
                "evidence-outline",
                "v1",
                true,
                "test outline skill",
                List.of("evidence_outline"),
                List.of("QUESTION", "PAGE"),
                List.of("search"),
                "只读证据大纲合同",
                Map.of("question", "string"),
                Map.of("type", "EVIDENCE_OUTLINE", "requiresEvidence", true, "minSections", 2),
                List.of("citation", "outline-structure"),
                "READ_ONLY",
                "NONE",
                30_000,
                3,
                "test-v1");
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
                        "MODEL_INTERRUPTED",
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
