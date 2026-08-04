package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.AgentJsonExtractor;
import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.config.AgentContextLabels;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentErrorMessages;
import com.chtholly.agent.config.AgentSystemPromptConfig;
import com.chtholly.agent.evidence.Evidence;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.observability.AgentToolDiagnostics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    void standardFinalActionReturnsReadyWithInitialTranscriptAndOnlyThinkEvent() throws Exception {
        String finalAction = "{\"action\":\"final\"}";
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(finalAction);
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

        JsonNode llmCall = objectMapper.valueToTree(trace.toPayloadMap().get("llmCalls")).path(0);
        JsonNode llmEvent = traceEvents(trace, "llm").getFirst();
        String expectedUserPrompt = "Earlier conversation\n\n"
                + "## Current question\nUser: What happened?";
        assertThat(llmCall.path("purpose").asText()).isEqualTo("LOOP_DECISION");
        assertThat(llmCall.path("model").asText()).isEqualTo("test-model");
        assertThat(llmCall.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(llmCall.path("attempt").asInt()).isEqualTo(1);
        assertThat(llmCall.path("budget_before_ms").asLong()).isZero();
        assertThat(llmCall.path("budget_after_ms").asLong()).isZero();
        assertThat(llmCall.path("input_chars").asInt())
                .isEqualTo("system prompt".length() + expectedUserPrompt.length());
        assertThat(llmCall.path("output_chars").asInt())
                .isEqualTo(finalAction.length());
        assertThat(llmEvent.path("phase").asText()).isEqualTo("llm");
        assertThat(llmEvent.path("details").path("purpose").asText()).isEqualTo("LOOP_DECISION");
        assertThat(llmEvent.path("details").path("systemPrompt").path("text").asText())
                .isEqualTo("system prompt");
        assertThat(llmEvent.path("details").path("userPrompt").path("text").asText())
                .isEqualTo(expectedUserPrompt);
        assertThat(llmEvent.path("details").path("rawOutput").path("text").asText())
                .isEqualTo(finalAction);
    }

    @Test
    void toolActionInjectsContextFeedsObservationAndRecordsTraceBeforeFinal() throws Exception {
        AgentTool tool = tool("search");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"search\",\"input\":{\"query\":\"re0\"}}")
                .thenReturn("{\"action\":\"final\"}");
        AgentToolDiagnostics diagnostics = AgentToolDiagnostics.standard(
                "search",
                Map.of("query", ParamDef.string("query", true, 1, 100)),
                Map.of("query", "re0"),
                "tool result");
        when(toolExecutor.execute(any(), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        "tool result", AgentToolResult.Status.SUCCESS, "", diagnostics));
        AgentExecutionTrace trace = trace(3);

        AgentLoopResult result = executor.execute(
                request(Map.of(tool.name(), tool), 3), trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        assertThat(result.transcript())
                .containsExactly(
                        "Earlier conversation",
                        "## Current question\nUser: What happened?",
                        "Observation: tool result")
                .noneMatch(entry -> entry.startsWith("Assistant:"));
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
        JsonNode toolEvent = traceEvents(trace, "tool").getFirst();
        assertThat(toolEvent.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(toolEvent.path("budget_before_ms").asLong()).isZero();
        assertThat(toolEvent.path("budget_after_ms").asLong()).isZero();
        assertThat(toolEvent.path("details").path("operation").asText()).isEqualTo("search");
        assertThat(toolEvent.path("details").path("sanitizedInput").path("query").asText())
                .isEqualTo("re0");
        assertThat(toolEvent.path("details").path("outputPreview").asText())
                .isEqualTo("tool result");
        assertThat(toolEvent.path("details").path("input").path("text").asText())
                .contains("\"query\":\"re0\"", "\"_userQuestion\":\"What happened?\"",
                        "\"_conversationHistory\":\"Earlier conversation\"");
        assertThat(toolEvent.path("details").path("observation").path("text").asText())
                .isEqualTo("tool result");
    }

    @Test
    void successfulToolEvidenceIsCitedInObservationAndCarriedToFinalResult() throws Exception {
        AgentTool tool = tool("web_fetch");
        Evidence evidence = Evidence.fromWebPage(
                "https://example.com/article", "Article", "content-hash", "web excerpt");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"web_fetch\",\"input\":{}}")
                .thenReturn("{\"action\":\"final\"}");
        when(toolExecutor.execute(any(), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        "page observation",
                        AgentToolResult.Status.SUCCESS,
                        "",
                        AgentToolDiagnostics.fallback("web_fetch", "page observation"),
                        List.of(evidence)));
        AgentExecutionTrace trace = trace(3);
        AgentLoopRequest request = new AgentLoopRequest(
                "system prompt", "question", 42L, "", Map.of(tool.name(), tool), 3,
                EvidenceSet.empty(), true);

        AgentLoopResult result = executor.execute(request, trace, agentSpan, events::add);

        assertThat(result.evidenceSet().items()).hasSize(1);
        assertThat(result.evidenceSet().items().getFirst().citationId()).isEqualTo("E1");
        assertThat(result.evidenceRequired()).isTrue();
        String observation = events.stream()
                .filter(event -> "observe".equals(event.type()))
                .findFirst().orElseThrow().data().path("content").asText();
        assertThat(observation).contains("page observation", "[E1]", "web excerpt");
        assertThat(traceEvents(trace, "tool").getFirst()
                .path("details").path("observation").path("text").asText())
                .isEqualTo(observation);
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llmInvoker, times(2)).call(anyString(), prompts.capture(), anyDouble(), anyInt());
        assertThat(prompts.getAllValues().get(1)).contains("[E1]", "web excerpt");
    }

    @Test
    void repeatedWebFetchReplacesChangedEvidenceAndReintroducesItsCitation() throws Exception {
        AgentTool tool = tool("web_fetch");
        Evidence first = Evidence.fromWebPage(
                "https://example.com/article", "First", "hash-one", "first excerpt");
        Evidence updated = Evidence.fromWebPage(
                "https://example.com/article", "Updated", "hash-two", "updated excerpt");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"web_fetch\",\"input\":{}}")
                .thenReturn("{\"action\":\"web_fetch\",\"input\":{}}")
                .thenReturn("{\"action\":\"final\"}");
        when(toolExecutor.execute(any(), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        webFetchObservation("https://example.com/article"),
                        AgentToolResult.Status.SUCCESS,
                        "",
                        AgentToolDiagnostics.fallback("web_fetch", "first page"),
                        List.of(first)))
                .thenReturn(new AgentToolResult(
                        webFetchObservation("https://example.com/article"),
                        AgentToolResult.Status.SUCCESS,
                        "",
                        AgentToolDiagnostics.fallback("web_fetch", "updated page"),
                        List.of(updated)));

        AgentLoopResult result = executor.execute(
                request(Map.of(tool.name(), tool), 4), trace(4), agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        assertThat(result.evidenceSet().items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.citationId()).isEqualTo("E1");
                    assertThat(item.sourceHash()).isEqualTo("hash-two");
                    assertThat(item.excerpt()).isEqualTo("updated excerpt");
                });
        List<String> observations = events.stream()
                .filter(event -> "observe".equals(event.type()))
                .map(event -> event.data().path("content").asText())
                .toList();
        assertThat(observations).hasSize(2);
        assertThat(observations.get(1)).contains("[E1]", "updated excerpt");
    }

    @Test
    void failedToolEvidenceIsDiscardedFromEveryTerminalSnapshot() throws Exception {
        AgentTool tool = tool("web_fetch");
        Evidence evidence = Evidence.fromWebPage(
                "https://example.com/article", "Article", "content-hash", "web excerpt");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"web_fetch\",\"input\":{}}")
                .thenReturn("{\"action\":\"final\"}");
        when(toolExecutor.execute(any(), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        "failed",
                        AgentToolResult.Status.ERROR,
                        "WEB_FAILED",
                        AgentToolDiagnostics.fallback("web_fetch", "failed"),
                        List.of(evidence)));

        AgentLoopResult result = executor.execute(
                request(Map.of(tool.name(), tool), 3), trace(3), agentSpan, events::add);

        assertThat(result.evidenceSet()).isSameAs(EvidenceSet.empty());
        assertThat(result.evidenceRequired()).isFalse();
        assertThat(events.stream()
                .filter(event -> "observe".equals(event.type()))
                .findFirst().orElseThrow().data().path("content").asText())
                .isEqualTo("failed");
    }

    @Test
    void webSearchCannotFinishUntilFetchedEvidenceIsAvailable() throws Exception {
        AgentTool search = tool("web_search");
        AgentTool fetch = tool("web_fetch");
        Evidence evidence = Evidence.fromWebPage(
                "https://example.com/article", "Article", "content-hash", "verified excerpt");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"web_search\",\"input\":{}}")
                .thenReturn("{\"action\":\"final\"}")
                .thenReturn("{\"action\":\"web_fetch\",\"input\":{}}")
                .thenReturn("{\"action\":\"final\"}");
        when(toolExecutor.execute(eq(search), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        webSearchObservation("https://example.com/article"),
                        AgentToolResult.Status.SUCCESS));
        when(toolExecutor.execute(eq(fetch), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        webFetchObservation("https://example.com/article"),
                        AgentToolResult.Status.SUCCESS,
                        "",
                        AgentToolDiagnostics.fallback("web_fetch", "page observation"),
                        List.of(evidence)));
        AgentExecutionTrace trace = trace(5);

        AgentLoopResult result = executor.execute(
                request(Map.of(search.name(), search, fetch.name(), fetch), 5),
                trace,
                agentSpan,
                events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        assertThat(result.evidenceSet().items()).hasSize(1);
        assertThat(result.evidenceRequired()).isTrue();
        assertThat(trace.getStepActions())
                .containsExactly("web_search", "web_fetch_pending", "web_fetch");
        assertThat(events.stream()
                .filter(event -> "observe".equals(event.type()))
                .map(event -> event.data().path("content").asText()))
                .anyMatch(message -> message.contains("WEB_RESEARCH_INCOMPLETE"));
        verify(toolExecutor).execute(eq(fetch), anyMap(), anyLong());
    }

    @Test
    void failedWebFetchDoesNotAuthorizeSearchOnlyFinalAnswer() throws Exception {
        AgentTool search = tool("web_search");
        AgentTool fetch = tool("web_fetch");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"web_search\",\"input\":{}}")
                .thenReturn("{\"action\":\"web_fetch\",\"input\":{}}")
                .thenReturn("{\"action\":\"final\"}");
        when(toolExecutor.execute(eq(search), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        webSearchObservation("https://example.com/article"),
                        AgentToolResult.Status.SUCCESS));
        when(toolExecutor.execute(eq(fetch), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult("fetch failed", AgentToolResult.Status.ERROR));
        AgentExecutionTrace trace = trace(3);

        AgentLoopResult result = executor.execute(
                request(Map.of(search.name(), search, fetch.name(), fetch), 3),
                trace,
                agentSpan,
                events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.MAX_STEPS);
        assertThat(result.evidenceSet()).isSameAs(EvidenceSet.empty());
        assertThat(trace.getStepActions())
                .containsExactly("web_search", "web_fetch", "web_fetch_pending");
        assertThat(events.stream()
                .filter(event -> "observe".equals(event.type()))
                .map(event -> event.data().path("content").asText()))
                .anyMatch(message -> message.contains("WEB_RESEARCH_INCOMPLETE"));
    }

    @Test
    void webSearchAfterEarlierFetchRequiresAFollowingFetch() throws Exception {
        AgentTool search = tool("web_search");
        AgentTool fetch = tool("web_fetch");
        Evidence evidence = Evidence.fromWebPage(
                "https://example.com/first", "First", "first-hash", "first excerpt");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"web_fetch\",\"input\":{}}")
                .thenReturn("{\"action\":\"web_search\",\"input\":{}}")
                .thenReturn("{\"action\":\"final\"}");
        when(toolExecutor.execute(eq(fetch), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        "first page",
                        AgentToolResult.Status.SUCCESS,
                        "",
                        AgentToolDiagnostics.fallback("web_fetch", "first page"),
                        List.of(evidence)));
        when(toolExecutor.execute(eq(search), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        webSearchObservation("https://example.com/second"),
                        AgentToolResult.Status.SUCCESS));
        AgentExecutionTrace trace = trace(3);

        AgentLoopResult result = executor.execute(
                request(Map.of(search.name(), search, fetch.name(), fetch), 3),
                trace,
                agentSpan,
                events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.MAX_STEPS);
        assertThat(trace.getStepActions())
                .containsExactly("web_fetch", "web_search", "web_fetch_pending");
    }

    @Test
    void unrelatedWebFetchDoesNotSatisfySearchCandidateRequirement() throws Exception {
        AgentTool search = tool("web_search");
        AgentTool fetch = tool("web_fetch");
        Evidence unrelated = Evidence.fromWebPage(
                "https://example.net/unrelated", "Unrelated", "other-hash", "other excerpt");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"web_search\",\"input\":{}}")
                .thenReturn("{\"action\":\"web_fetch\",\"input\":{}}")
                .thenReturn("{\"action\":\"final\"}");
        when(toolExecutor.execute(eq(search), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        webSearchObservation("https://example.com/candidate"),
                        AgentToolResult.Status.SUCCESS));
        when(toolExecutor.execute(eq(fetch), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        webFetchObservation("https://example.net/unrelated"),
                        AgentToolResult.Status.SUCCESS,
                        "",
                        AgentToolDiagnostics.fallback("web_fetch", "other page"),
                        List.of(unrelated)));
        AgentExecutionTrace trace = trace(3);

        AgentLoopResult result = executor.execute(
                request(Map.of(search.name(), search, fetch.name(), fetch), 3),
                trace,
                agentSpan,
                events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.MAX_STEPS);
        assertThat(result.evidenceSet().items()).singleElement()
                .extracting(Evidence::documentId)
                .isEqualTo("https://example.net/unrelated");
        assertThat(trace.getStepActions())
                .containsExactly("web_search", "web_fetch", "web_fetch_pending");
    }

    @Test
    void consecutiveWebSearchesKeepEarlierCandidatesFetchable() throws Exception {
        AgentTool search = tool("web_search");
        AgentTool fetch = tool("web_fetch");
        Evidence firstSearchEvidence = Evidence.fromWebPage(
                "https://example.com/first", "First", "first-hash", "first excerpt");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"web_search\",\"input\":{}}")
                .thenReturn("{\"action\":\"web_search\",\"input\":{}}")
                .thenReturn("{\"action\":\"web_fetch\",\"input\":{}}")
                .thenReturn("{\"action\":\"final\"}");
        when(toolExecutor.execute(eq(search), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        webSearchObservation("HTTPS://Example.COM:443/first"),
                        AgentToolResult.Status.SUCCESS))
                .thenReturn(new AgentToolResult(
                        webSearchObservation("https://example.com/second"),
                        AgentToolResult.Status.SUCCESS));
        when(toolExecutor.execute(eq(fetch), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        webFetchObservation("https://example.com/first"),
                        AgentToolResult.Status.SUCCESS,
                        "",
                        AgentToolDiagnostics.fallback("web_fetch", "first page"),
                        List.of(firstSearchEvidence)));
        AgentExecutionTrace trace = trace(4);

        AgentLoopResult result = executor.execute(
                request(Map.of(search.name(), search, fetch.name(), fetch), 4),
                trace,
                agentSpan,
                events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        assertThat(result.evidenceSet().items()).singleElement()
                .extracting(Evidence::documentId)
                .isEqualTo("https://example.com/first");
        assertThat(trace.getStepActions())
                .containsExactly("web_search", "web_search", "web_fetch");
    }

    @Test
    void newlyVisibleCandidateFromALaterSearchRemainsFetchable() throws Exception {
        AgentTool search = tool("web_search");
        AgentTool fetch = tool("web_fetch");
        Evidence overflowEvidence = Evidence.fromWebPage(
                "https://example.com/ninth", "Ninth", "ninth-hash", "ninth excerpt");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"web_search\",\"input\":{}}")
                .thenReturn("{\"action\":\"web_search\",\"input\":{}}")
                .thenReturn("{\"action\":\"web_fetch\",\"input\":{}}")
                .thenReturn("{\"action\":\"final\"}");
        when(toolExecutor.execute(eq(search), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        webSearchObservation(
                                "https://example.com/first",
                                "https://example.com/second",
                                "https://example.com/third",
                                "https://example.com/fourth",
                                "https://example.com/fifth",
                                "https://example.com/sixth",
                                "https://example.com/seventh",
                                "https://example.com/eighth"),
                        AgentToolResult.Status.SUCCESS))
                .thenReturn(new AgentToolResult(
                        webSearchObservation("https://example.com/ninth"),
                        AgentToolResult.Status.SUCCESS));
        when(toolExecutor.execute(eq(fetch), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult(
                        webFetchObservation("https://example.com/ninth"),
                        AgentToolResult.Status.SUCCESS,
                        "",
                        AgentToolDiagnostics.fallback("web_fetch", "ninth page"),
                        List.of(overflowEvidence)));
        AgentExecutionTrace trace = trace(4);

        AgentLoopResult result = executor.execute(
                request(Map.of(search.name(), search, fetch.name(), fetch), 4),
                trace,
                agentSpan,
                events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        assertThat(result.evidenceSet().items()).singleElement()
                .extracting(Evidence::documentId)
                .isEqualTo("https://example.com/ninth");
        assertThat(trace.getStepActions())
                .containsExactly("web_search", "web_search", "web_fetch");
    }

    @Test
    void compoundBangumiQuestionCannotFinishBeforeCharactersToolRuns() throws Exception {
        AgentTool search = tool("bangumi_search");
        AgentTool characters = tool("bangumi_characters");
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"bangumi_search\",\"input\":{\"keyword\":\"迷宫饭\"}}")
                .thenReturn("{\"action\":\"final\"}")
                .thenReturn("{\"action\":\"bangumi_characters\",\"input\":{\"keyword\":\"迷宫饭\"}}")
                .thenReturn("{\"action\":\"final\"}");
        when(toolExecutor.execute(eq(search), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult("评分 8.1，共 24 集", AgentToolResult.Status.SUCCESS));
        when(toolExecutor.execute(eq(characters), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult("主要角色：莱欧斯、玛露希尔", AgentToolResult.Status.SUCCESS));
        AgentExecutionTrace trace = trace(5);
        AgentLoopRequest request = new AgentLoopRequest(
                "system",
                "查询《迷宫饭》的评分、集数和放送时间。《迷宫饭》的主要角色有哪些？",
                7L,
                "",
                Map.of(search.name(), search, characters.name(), characters),
                5);

        AgentLoopResult result = executor.execute(request, trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        verify(toolExecutor).execute(eq(search), anyMap(), anyLong());
        verify(toolExecutor).execute(eq(characters), anyMap(), anyLong());
        assertThat(trace.getStepActions())
                .containsExactly("bangumi_search", "compound_tool_pending", "bangumi_characters");
        assertThat(events.stream()
                .filter(event -> "observe".equals(event.type()))
                .map(event -> event.data().path("content").asText()))
                .anyMatch(message -> message.contains("bangumi_characters"));
    }

    @Test
    void compoundBangumiQuestionAcceptsFinalActionWithLiteralLineBreaks() throws Exception {
        AgentTool search = tool("bangumi_search");
        AgentTool characters = tool("bangumi_characters");
        String finalAction = """
                {"action":"final","answer":"评分 7.80，共 24 集。

                主要角色：
                - 莱欧斯
                - 玛露希尔"}
                """;
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"bangumi_search\",\"input\":{\"keyword\":\"迷宫饭\"}}")
                .thenReturn("{\"action\":\"bangumi_characters\",\"input\":{\"keyword\":\"迷宫饭\"}}")
                .thenReturn(finalAction);
        when(toolExecutor.execute(eq(search), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult("评分 7.80，共 24 集", AgentToolResult.Status.SUCCESS));
        when(toolExecutor.execute(eq(characters), anyMap(), anyLong()))
                .thenReturn(new AgentToolResult("主要角色：莱欧斯、玛露希尔", AgentToolResult.Status.SUCCESS));
        AgentExecutionTrace trace = trace(3);
        AgentLoopRequest request = new AgentLoopRequest(
                "system",
                "查询《迷宫饭》的评分、集数、放送时间并列出角色",
                7L,
                "",
                Map.of(search.name(), search, characters.name(), characters),
                3);

        AgentLoopResult result = executor.execute(request, trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        assertThat(trace.getStepActions()).containsExactly("bangumi_search", "bangumi_characters");
        assertThat(trace.getStepActions()).doesNotContain("parse_error");
        JsonNode finalDecisionEvent = traceEvents(trace, "llm").get(2);
        assertThat(finalDecisionEvent.path("details").path("rawOutput").path("text").asText())
                .isEqualTo(finalAction)
                .contains("评分 7.80，共 24 集。\n\n主要角色：");
        verify(llmInvoker, times(3)).call(anyString(), anyString(), anyDouble(), anyInt());
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
        assertThat(result.transcript()).containsExactly(
                "Earlier conversation",
                "## Current question\nUser: What happened?");
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
        JsonNode llmEvent = traceEvents(trace, "llm").getFirst();
        assertThat(llmEvent.path("status").asText()).isEqualTo("TIMEOUT");
        assertThat(llmEvent.path("error_code").asText()).isEqualTo("LLM_TIMEOUT");
        assertThat(llmEvent.path("attempt").asInt()).isEqualTo(1);
        verify(observationService).finishSpanError(
                eq(childSpan), eq("llm_timeout"), anyMap(), anyMap());
    }

    @Test
    void turnBudgetIsPassedToLlmAndCanExpireBeforeFirstStep() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AgentTurnBudget expired = AgentTurnBudget.start(
                Duration.ofNanos(1), cancelled::get);
        Thread.sleep(1);
        AgentLoopRequest request = new AgentLoopRequest(
                "system prompt",
                "What happened?",
                42L,
                "Earlier conversation",
                Map.of(),
                3,
                expired);
        AgentExecutionTrace trace = trace(3);

        AgentLoopResult result = executor.execute(request, trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.TURN_TIMEOUT);
        assertThat(trace.getTerminatedBy()).isEqualTo("timeout");
        verify(llmInvoker, never()).call(
                anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class));
    }

    @Test
    void activeTurnBudgetBoundsLlmInvocation() throws Exception {
        when(llmInvoker.call(
                anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenReturn("{\"action\":\"final\"}");
        AgentTurnBudget budget = AgentTurnBudget.start(
                Duration.ofSeconds(10), () -> false);
        AgentLoopRequest request = new AgentLoopRequest(
                "system prompt",
                "What happened?",
                42L,
                "Earlier conversation",
                Map.of(),
                3,
                budget);

        AgentExecutionTrace trace = trace(3);
        AgentLoopResult result = executor.execute(request, trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(llmInvoker).call(
                anyString(), anyString(), anyDouble(), anyInt(), timeout.capture());
        assertThat(timeout.getValue()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(10));
        JsonNode llmEvent = traceEvents(trace, "llm").getFirst();
        assertThat(llmEvent.path("budget_before_ms").asLong()).isPositive();
        assertThat(llmEvent.path("budget_after_ms").asLong())
                .isBetween(0L, llmEvent.path("budget_before_ms").asLong());
    }

    @Test
    void turnBudgetExpiryDuringLlmAdmissionRecordsStructuredFailure() throws Exception {
        AtomicInteger clockReads = new AtomicInteger();
        AgentTurnBudget budget = AgentTurnBudget.start(
                Duration.ofMillis(1),
                () -> false,
                () -> clockReads.getAndIncrement() < 2 ? 0L : Duration.ofMillis(2).toNanos());
        AgentExecutionTrace trace = trace(3);
        AgentLoopRequest request = new AgentLoopRequest(
                "system prompt", "private question", 42L, "private history", Map.of(), 3, budget);

        AgentLoopResult result = executor.execute(request, trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.TURN_TIMEOUT);
        verify(llmInvoker, never()).call(
                anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class));
        JsonNode llmEvent = traceEvents(trace, "llm").getFirst();
        assertThat(llmEvent.path("status").asText()).isEqualTo("TIMEOUT");
        assertThat(llmEvent.path("error_code").asText()).isEqualTo("TURN_TIMEOUT");
        assertThat(llmEvent.path("attempt").asInt()).isEqualTo(1);
        assertThat(llmEvent.path("budget_before_ms").asLong()).isZero();
        assertThat(llmEvent.path("budget_after_ms").asLong()).isZero();
        assertThat(trace.toPayloadMap().toString())
                .contains("private question", "private history");
    }

    @Test
    void turnCancellationDuringLlmAdmissionRecordsStructuredFailure() {
        AtomicInteger cancellationChecks = new AtomicInteger();
        AgentTurnBudget budget = AgentTurnBudget.start(
                Duration.ofSeconds(1),
                () -> cancellationChecks.getAndIncrement() > 0);
        AgentExecutionTrace trace = trace(3);
        AgentLoopRequest request = new AgentLoopRequest(
                "system prompt", "private question", 42L, "private history", Map.of(), 3, budget);

        AgentLoopResult result = executor.execute(request, trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.CANCELLED);
        JsonNode llmEvent = traceEvents(trace, "llm").getFirst();
        assertThat(llmEvent.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(llmEvent.path("error_code").asText()).isEqualTo("TURN_CANCELLED");
        assertThat(llmEvent.path("attempt").asInt()).isEqualTo(1);
    }

    @Test
    void cancelledTurnStopsBeforeCallingLlm() throws Exception {
        AgentTurnBudget cancelled = AgentTurnBudget.start(
                Duration.ofSeconds(10), () -> true);
        AgentLoopRequest request = new AgentLoopRequest(
                "system prompt",
                "What happened?",
                42L,
                "Earlier conversation",
                Map.of(),
                3,
                cancelled);

        AgentLoopResult result = executor.execute(request, trace(3), agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.CANCELLED);
        verify(llmInvoker, never()).call(
                anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class));
    }

    @Test
    void interruptedLlmIsClassifiedAsCancellationWhenTheTurnWasCancelled() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AgentTurnBudget budget = AgentTurnBudget.start(
                Duration.ofSeconds(10), cancelled::get);
        when(llmInvoker.call(
                anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    cancelled.set(true);
                    throw new InterruptedException("socket closed");
                });
        AgentExecutionTrace trace = trace(3);
        AgentLoopRequest request = new AgentLoopRequest(
                "system prompt",
                "What happened?",
                42L,
                "",
                Map.of(),
                3,
                budget);

        try {
            AgentLoopResult result = executor.execute(request, trace, agentSpan, events::add);

            assertThat(result.status()).isEqualTo(AgentLoopResult.Status.CANCELLED);
            assertThat(trace.getTerminatedBy()).isEqualTo("cancelled");
            assertThat(trace.getTimeoutStage()).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            JsonNode llmEvent = traceEvents(trace, "llm").getFirst();
            assertThat(llmEvent.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(llmEvent.path("error_code").asText()).isEqualTo("TURN_CANCELLED");
        } finally {
            Thread.interrupted();
        }
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
        JsonNode llmEvent = traceEvents(trace, "llm").getFirst();
        assertThat(llmEvent.path("status").asText()).isEqualTo("ERROR");
        assertThat(llmEvent.path("error_code").asText()).isEqualTo("LLM_ERROR");
        verify(observationService).finishSpanError(
                eq(childSpan), eq("llm_error"), anyMap(), anyMap());
    }

    @Test
    void transientLlmFailureRetriesOnceAndRecordsBothAttempts() throws Exception {
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenThrow(new ResourceAccessException("connection reset"))
                .thenReturn("{\"action\":\"final\"}");
        AgentExecutionTrace trace = trace(2);

        AgentLoopResult result = executor.execute(
                request(Map.of(), 2), trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        verify(llmInvoker, times(2)).call(anyString(), anyString(), anyDouble(), anyInt());
        JsonNode llmCalls = objectMapper.valueToTree(trace.toPayloadMap().get("llmCalls"));
        assertThat(llmCalls).hasSize(2);
        assertThat(llmCalls.path(0).path("purpose").asText()).isEqualTo("LOOP_DECISION");
        assertThat(llmCalls.path(0).path("status").asText()).isEqualTo("ERROR");
        assertThat(llmCalls.path(0).path("error_code").asText())
                .isEqualTo("LLM_TRANSIENT_ERROR");
        assertThat(llmCalls.path(0).path("attempt").asInt()).isEqualTo(1);
        assertThat(llmCalls.path(1).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(llmCalls.path(1).path("attempt").asInt()).isEqualTo(2);
        assertThat(eventTypes()).containsExactly("think");
    }

    @Test
    void wrappedTimeoutOutranksTransientWrapperAndIsNotRetried() throws Exception {
        RuntimeException wrappedTimeout = new RuntimeException(
                "connection reset",
                new TimeoutException("wrapped timeout secret"));
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenThrow(wrappedTimeout)
                .thenReturn("{\"action\":\"final\"}");
        AgentExecutionTrace trace = trace(2);

        AgentLoopResult result = executor.execute(
                request(Map.of(), 2), trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.LLM_TIMEOUT);
        verify(llmInvoker, times(1)).call(anyString(), anyString(), anyDouble(), anyInt());
        JsonNode llmCalls = objectMapper.valueToTree(trace.toPayloadMap().get("llmCalls"));
        assertThat(llmCalls).hasSize(1);
        assertThat(llmCalls.path(0).path("status").asText()).isEqualTo("TIMEOUT");
        assertThat(llmCalls.path(0).path("error_code").asText()).isEqualTo("LLM_TIMEOUT");
        assertThat(llmCalls.path(0).path("attempt").asInt()).isEqualTo(1);
        assertThat(trace.toPayloadMap().toString()).contains("wrapped timeout secret");
    }

    @Test
    void wrappedInterruptedOutranksTransientWrapperAndIsNotRetried() throws Exception {
        RuntimeException wrappedInterrupted = new RuntimeException(
                "temporarily unavailable",
                new InterruptedException("wrapped interrupt secret"));
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenThrow(wrappedInterrupted)
                .thenReturn("{\"action\":\"final\"}");
        AgentExecutionTrace trace = trace(2);

        try {
            AgentLoopResult result = executor.execute(
                    request(Map.of(), 2), trace, agentSpan, events::add);

            assertThat(result.status()).isEqualTo(AgentLoopResult.Status.LLM_INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(llmInvoker, times(1)).call(anyString(), anyString(), anyDouble(), anyInt());
            JsonNode llmCalls = objectMapper.valueToTree(trace.toPayloadMap().get("llmCalls"));
            assertThat(llmCalls).hasSize(1);
            assertThat(llmCalls.path(0).path("status").asText()).isEqualTo("INTERRUPTED");
            assertThat(llmCalls.path(0).path("error_code").asText()).isEqualTo("LLM_INTERRUPTED");
            assertThat(llmCalls.path(0).path("attempt").asInt()).isEqualTo(1);
            assertThat(trace.toPayloadMap().toString()).contains("wrapped interrupt secret");
        } finally {
            Thread.interrupted();
        }
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
            JsonNode llmEvent = traceEvents(trace, "llm").getFirst();
            assertThat(llmEvent.path("status").asText()).isEqualTo("INTERRUPTED");
            assertThat(llmEvent.path("error_code").asText()).isEqualTo("LLM_INTERRUPTED");
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
        JsonNode toolEvent = traceEvents(trace, "tool").getFirst();
        assertThat(toolEvent.path("status").asText()).isEqualTo("TIMEOUT");
        assertThat(toolEvent.path("error_code").asText()).isEqualTo("TOOL_TIMEOUT");
        assertThat(toolEvent.path("details").path("observation").path("text").asText())
                .isEqualTo("timed out\n\nbangumi guidance");
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
        when(tool.parameterSchema()).thenReturn(Map.of(
                "query", ParamDef.string("query", true, 1, 100)));
        when(llmInvoker.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"action\":\"search\",\"input\":{\"query\":\"safe\"}}");
        when(toolExecutor.execute(any(), anyMap(), anyLong()))
                .thenThrow(new RejectedExecutionException("executor saturated secret-marker"));
        AgentExecutionTrace trace = trace(3);

        assertThatThrownBy(() -> executor.execute(
                request(Map.of(tool.name(), tool), 3), trace, agentSpan, events::add))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessage("executor saturated secret-marker");

        verify(observationService).finishSpanError(
                childSpan,
                "tool_executor_error",
                Map.of("status", "error", "error.type", "INTERNAL_ERROR"),
                Map.of());
        JsonNode toolEvent = traceEvents(trace, "tool").getFirst();
        assertThat(toolEvent.path("status").asText()).isEqualTo("ERROR");
        assertThat(toolEvent.path("error_code").asText()).isEqualTo("TOOL_EXECUTOR_ERROR");
        assertThat(toolEvent.path("details").path("sanitizedInput").path("query").asText())
                .isEqualTo("safe");
        assertThat(toolEvent.path("details").path("sanitizedInput").has("_userQuestion")).isFalse();
        assertThat(toolEvent.path("details").path("sanitizedInput").has("_conversationHistory")).isFalse();
        assertThat(toolEvent.path("details").path("outputPreview").asText()).isEmpty();
        assertThat(toolEvent.path("details").path("input").path("text").asText())
                .contains("\"query\":\"safe\"", "What happened?", "Earlier conversation");
    }

    @Test
    void activeToolCallRecordsExactBudgetsAndStructuredDiagnostics() throws Exception {
        AtomicLong clock = new AtomicLong();
        AgentTurnBudget budget = AgentTurnBudget.start(
                Duration.ofMillis(100), () -> false, clock::get);
        AgentTool tool = tool("search");
        when(tool.parameterSchema()).thenReturn(Map.of(
                "query", ParamDef.string("query", true, 1, 100)));
        when(llmInvoker.call(
                anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    clock.addAndGet(Duration.ofMillis(2).toNanos());
                    return "{\"action\":\"search\",\"input\":{\"query\":\"safe\"}}";
                })
                .thenReturn("{\"action\":\"final\"}");
        AgentToolDiagnostics diagnostics = AgentToolDiagnostics.standard(
                "search", tool.parameterSchema(), Map.of("query", "safe"), "result")
                .withProvider("mysql")
                .withSourcePolicy("public_only")
                .withResultCount(1)
                .withSelectedIds(List.of("post:1"));
        when(toolExecutor.execute(any(), anyMap(), anyLong(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    clock.addAndGet(Duration.ofMillis(3).toNanos());
                    return new AgentToolResult("result", AgentToolResult.Status.SUCCESS, "", diagnostics);
                });
        AgentExecutionTrace trace = trace(3);
        AgentLoopRequest request = new AgentLoopRequest(
                "system prompt", "question", 42L, "", Map.of(tool.name(), tool), 3, budget);

        AgentLoopResult result = executor.execute(request, trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.FINAL_READY);
        JsonNode toolEvent = traceEvents(trace, "tool").getFirst();
        assertThat(toolEvent.path("budget_before_ms").asLong()).isEqualTo(98L);
        assertThat(toolEvent.path("budget_after_ms").asLong()).isEqualTo(95L);
        assertThat(toolEvent.path("details").path("provider").asText()).isEqualTo("mysql");
        assertThat(toolEvent.path("details").path("sourcePolicy").asText()).isEqualTo("public_only");
        assertThat(toolEvent.path("details").path("resultCount").asInt()).isEqualTo(1);
        assertThat(toolEvent.path("details").path("selectedIds").path(0).asText())
                .isEqualTo("post:1");
    }

    @Test
    void toolBudgetExpiryRecordsSafeStructuredTimeoutBeforeReturning() throws Exception {
        AtomicLong clock = new AtomicLong();
        AgentTurnBudget budget = AgentTurnBudget.start(
                Duration.ofMillis(10), () -> false, clock::get);
        AgentTool tool = tool("search");
        when(tool.parameterSchema()).thenReturn(Map.of(
                "query", ParamDef.string("query", true, 1, 100)));
        when(llmInvoker.call(
                anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    clock.set(Duration.ofMillis(11).toNanos());
                    return "{\"action\":\"search\",\"input\":{\"query\":\"safe\"}}";
                });
        AgentExecutionTrace trace = trace(3);
        AgentLoopRequest request = new AgentLoopRequest(
                "system prompt", "private question", 42L, "private history",
                Map.of(tool.name(), tool), 3, budget);

        AgentLoopResult result = executor.execute(request, trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.TURN_TIMEOUT);
        verify(toolExecutor, never()).execute(any(), anyMap(), anyLong(), any(Duration.class));
        JsonNode toolEvent = traceEvents(trace, "tool").getFirst();
        assertThat(toolEvent.path("status").asText()).isEqualTo("TIMEOUT");
        assertThat(toolEvent.path("error_code").asText()).isEqualTo("TURN_TIMEOUT");
        assertThat(toolEvent.path("budget_before_ms").asLong()).isZero();
        assertThat(toolEvent.path("budget_after_ms").asLong()).isZero();
        assertThat(toolEvent.path("details").path("sanitizedInput").path("query").asText())
                .isEqualTo("safe");
        assertThat(toolEvent.path("details").path("sanitizedInput").has("_userQuestion")).isFalse();
        assertThat(toolEvent.path("details").path("sanitizedInput").has("_conversationHistory")).isFalse();
        assertThat(toolEvent.path("details").path("outputPreview").asText()).isEmpty();
        assertThat(trace.toPayloadMap().toString())
                .contains("private question", "private history");
    }

    @Test
    void toolAdmissionCancellationRecordsSafeStructuredInterruptionBeforeReturning() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicBoolean cancelled = new AtomicBoolean();
        AgentTurnBudget budget = AgentTurnBudget.start(
                Duration.ofMillis(10), cancelled::get, clock::get);
        AgentTool tool = tool("search");
        when(tool.parameterSchema()).thenReturn(Map.of(
                "query", ParamDef.string("query", true, 1, 100)));
        when(llmInvoker.call(
                anyString(), anyString(), anyDouble(), anyInt(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    cancelled.set(true);
                    return "{\"action\":\"search\",\"input\":{\"query\":\"safe\"}}";
                });
        AgentExecutionTrace trace = trace(3);
        AgentLoopRequest request = new AgentLoopRequest(
                "system prompt", "private question", 42L, "private history",
                Map.of(tool.name(), tool), 3, budget);

        AgentLoopResult result = executor.execute(request, trace, agentSpan, events::add);

        assertThat(result.status()).isEqualTo(AgentLoopResult.Status.CANCELLED);
        verify(toolExecutor, never()).execute(any(), anyMap(), anyLong(), any(Duration.class));
        JsonNode toolEvent = traceEvents(trace, "tool").getFirst();
        assertThat(toolEvent.path("status").asText()).isEqualTo("INTERRUPTED");
        assertThat(toolEvent.path("error_code").asText()).isEqualTo("TURN_CANCELLED");
        assertThat(toolEvent.path("budget_before_ms").asLong()).isEqualTo(10L);
        assertThat(toolEvent.path("budget_after_ms").asLong()).isEqualTo(10L);
        assertThat(toolEvent.path("details").path("sanitizedInput").path("query").asText())
                .isEqualTo("safe");
        assertThat(toolEvent.path("details").path("sanitizedInput").has("_userQuestion")).isFalse();
        assertThat(toolEvent.path("details").path("sanitizedInput").has("_conversationHistory")).isFalse();
        assertThat(toolEvent.path("details").path("outputPreview").asText()).isEmpty();
        assertThat(trace.toPayloadMap().toString())
                .contains("private question", "private history");
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

    private List<JsonNode> traceEvents(AgentExecutionTrace trace, String type) {
        List<JsonNode> matching = new ArrayList<>();
        JsonNode traceEvents = objectMapper.valueToTree(trace.toPayloadMap().get("events"));
        traceEvents.forEach(event -> {
            if (type.equals(event.path("type").asText())) {
                matching.add(event);
            }
        });
        return matching;
    }

    private AgentTool tool(String name) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn(name);
        return tool;
    }

    private String webSearchObservation(String... urls) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("kind", "web_search_results");
        var results = root.putArray("results");
        for (String url : urls) {
            results.addObject().put("url", url);
        }
        return objectMapper.writeValueAsString(root);
    }

    private String webFetchObservation(String requestedUrl) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("kind", "web_fetched_page");
        root.put("requestedUrl", requestedUrl);
        return objectMapper.writeValueAsString(root);
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
