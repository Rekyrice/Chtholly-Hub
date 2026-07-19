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
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentMetrics;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.runtime.AgentLoopExecutor;
import com.chtholly.agent.runtime.AgentLoopRequest;
import com.chtholly.agent.runtime.AgentLoopResult;
import com.chtholly.agent.skill.SkillDefinition;
import com.chtholly.agent.skill.SkillExecutionContext;
import com.chtholly.agent.skill.SkillOutputValidator;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        properties.setStreamCharDelayMs(0);
        properties.setModel("test-model");
        domainConfig = domainConfig();
        when(observationService.startAgentSpan(anyString(), anyLong())).thenReturn(agentSpan);
        agent = new ChthollyAgent(
                llmInvoker,
                loopExecutor,
                properties,
                new ObjectMapper(),
                List.of(tool("search"), tool("draft_write")),
                agentMetrics,
                observationService,
                new CharacterSoulService("soul"),
                contextEngine,
                tracePersistenceService,
                domainConfig,
                skillRegistry,
                skillSelector,
                new SkillOutputValidator());
        events = new ArrayList<>();
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
                Set.of("search")));
        when(contextEngine.buildSnapshot(
                anyLong(), anyString(), anyString(), any(), anyString(), anyString(), anyBoolean()))
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
                .contains("skillId=page-explain", "skillVersion=v1", "只读合同");
        assertThat(requestCaptor.getValue().maxSteps()).isEqualTo(3);
    }

    @Test
    void finalReadyStreamsAnswerWritesMemoryAndPersistsSuccessfulTrace() {
        when(memory.formatForPrompt()).thenReturn("history");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(snapshot("assembled system"));
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
        assertThat(eventTypes()).containsExactly("delta", "final");
        ArgumentCaptor<String> finalSystemCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvoker).stream(finalSystemCaptor.capture(), anyString(), eq(0.3), eq(1024));
        assertThat(finalSystemCaptor.getValue()).contains("assembled system", "Answer with soul");
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
    void answerSinkFailureDoesNotFinishSuccessfulLlmSpanTwice() {
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(snapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any()))
                .thenReturn(AgentLoopResult.finalReady(List.of("current question"), 1, 10));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt()))
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
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), eq(true)))
                .thenReturn(groundedSnapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any())).thenReturn(
                AgentLoopResult.finalReady(List.of("current question"), 1, 10));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(Flux.just("有依据 [E1]"));

        agent.run("解释这个页面", 7L, memory, "session", "页面", "page-explain", events::add);

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmInvoker).stream(systemCaptor.capture(), anyString(), eq(0.3), eq(1024));
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
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(Flux.just("伪造事实 [E999]"));

        agent.run("帮我查站内事实", 7L, memory, events::add);

        assertThat(eventTypes()).containsExactly("delta", "final");
        assertThat(eventContents())
                .containsOnly(EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER)
                .noneMatch(content -> content.contains("E999") || content.contains("伪造"));
        ArgumentCaptor<AgentTurn> turnCaptor = ArgumentCaptor.forClass(AgentTurn.class);
        verify(memory, times(2)).add(turnCaptor.capture());
        assertThat(turnCaptor.getAllValues().get(1).content())
                .isEqualTo(EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER);
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        assertThat(traceCaptor.getValue().getFinalAnswerLength())
                .isEqualTo(EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER.length());
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap());
        assertThat(payload.path("retrieval").path("citationValidationStatus").asText())
                .isEqualTo("UNKNOWN_CITATION");
        assertThat(payload.path("failureType").asText()).isEqualTo("CITATION_INVALID");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> retrievalLow = ArgumentCaptor.forClass(Map.class);
        verify(observationService).finishSpanError(
                eq(retrievalSpan), eq("retrieval_failed"), retrievalLow.capture(), anyMap());
        assertThat(retrievalLow.getValue())
                .containsEntry("status", "error")
                .containsEntry("error.type", "CITATION_INVALID");
    }

    @Test
    void missingCitationFallsBackBeforeAnyAnswerEvent() {
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(groundedSnapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any())).thenReturn(
                AgentLoopResult.finalReady(List.of("current question"), 1, 10));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(Flux.just("没有引用的站内事实"));

        agent.run("帮我查站内事实", 7L, memory, events::add);

        assertThat(eventContents()).containsOnly(EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER);
    }

    @Test
    void requiredEvidenceEmptyShortCircuitsLoopAndLlm() {
        when(memory.formatForPrompt()).thenReturn("");
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new AgentContextSnapshot("assembled system", EvidenceSet.empty(), true));

        agent.run("帮我查站内事实", 7L, memory, events::add);

        verify(loopExecutor, never()).execute(any(), any(), any(), any());
        verify(llmInvoker, never()).stream(anyString(), anyString(), anyDouble(), anyInt());
        assertThat(eventTypes()).containsExactly("delta", "final");
        assertThat(eventContents()).containsOnly(EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER);
        ArgumentCaptor<AgentTurn> turnCaptor = ArgumentCaptor.forClass(AgentTurn.class);
        verify(memory, times(2)).add(turnCaptor.capture());
        assertThat(turnCaptor.getAllValues()).extracting(AgentTurn::content)
                .containsExactly("帮我查站内事实", EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER);
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap());
        assertThat(payload.path("retrieval").path("citationValidationStatus").asText())
                .isEqualTo("NO_EVIDENCE");
        assertThat(payload.path("failureType").asText()).isEqualTo("RETRIEVAL_EMPTY");
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

        agent.run("帮我查站内事实", 7L, memory, events::add);

        verify(loopExecutor, never()).execute(any(), any(), any(), any());
        verify(llmInvoker, never()).stream(anyString(), anyString(), anyDouble(), anyInt());
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
        when(contextEngine.buildSnapshot(anyLong(), any(), any(), any(), anyString(), anyString(), eq(true)))
                .thenReturn(groundedSnapshot("assembled system"));
        when(loopExecutor.execute(any(), any(), any(), any())).thenReturn(
                AgentLoopResult.finalReady(List.of("current question"), 1, 10));
        when(observationService.startLlmSpan(agentSpan, "test-model")).thenReturn(llmSpan);
        when(llmInvoker.stream(anyString(), anyString(), anyDouble(), anyInt()))
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
    void clarificationRequiredIsClassifiedWithoutEnteringContextOrModel() {
        when(skillRegistry.enabled()).thenReturn(List.of());
        when(skillSelector.select(any(), any())).thenReturn(new SkillSelector.SkillSelection(
                SkillSelector.Status.CLARIFICATION_REQUIRED,
                null,
                "unknown_or_ambiguous_task_type",
                0.0,
                Set.of()));

        agent.run("执行未知任务", 7L, memory, "session", "页面", "unknown", events::add);

        verify(contextEngine, never()).buildSnapshot(
                anyLong(), any(), any(), any(), anyString(), anyString(), anyBoolean());
        verify(loopExecutor, never()).execute(any(), any(), any(), any());
        verify(llmInvoker, never()).stream(anyString(), anyString(), anyDouble(), anyInt());
        assertThat(eventTypes()).containsExactly("final");
        ArgumentCaptor<AgentExecutionTrace> traceCaptor = ArgumentCaptor.forClass(AgentExecutionTrace.class);
        verify(tracePersistenceService).persist(traceCaptor.capture());
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().valueToTree(
                traceCaptor.getValue().toPayloadMap());
        assertThat(payload.path("skill").path("selectionStatus").asText())
                .isEqualTo("CLARIFICATION_REQUIRED");
        assertThat(payload.path("failureType").asText()).isEqualTo("SKILL_NO_MATCH");
    }

    private List<String> eventTypes() {
        return events.stream().map(AgentEvent::type).toList();
    }

    private List<String> eventContents() {
        return events.stream().map(event -> event.data().path("content").asText()).toList();
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
