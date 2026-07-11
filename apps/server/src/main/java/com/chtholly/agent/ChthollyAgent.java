package com.chtholly.agent;

import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.context.ContextEngine;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentMetrics;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.runtime.AgentToolExecutor;
import com.chtholly.agent.runtime.AgentToolResult;
import com.chtholly.agent.trace.TracePersistenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.Observation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Custom ReAct agent engine: Think -> Act -> Observe loop until final answer or step limit.
 *
 * <p>Design: model calls are delegated to {@link AgentLlmInvoker}; tool execution is isolated
 * with per-tool timeout. Final answers stream over WebSocket with optional character throttling.
 * Observability is provided by {@link AgentExecutionTrace} and {@link AgentMetrics}.
 *
 * @see AgentTool
 * @see AgentConversationMemory
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ChthollyAgent {

    private final AgentLlmInvoker llmInvoker;
    private final AgentToolExecutor agentToolExecutor;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final List<AgentTool> tools;
    private final AgentJsonExtractor jsonExtractor;
    private final AgentMetrics agentMetrics;
    private final AgentObservationService agentObservationService;
    private final CharacterSoulService characterSoulService;
    private final ContextEngine contextEngine;
    private final TracePersistenceService tracePersistenceService;
    private final AgentDomainConfig agentDomainConfig;

    /**
     * Runs one agent turn, emitting think/act/observe/delta/final/error events via sink.
     *
     * @param question User question for this turn.
     * @param userId   Authenticated user ID (passed to tools).
     * @param memory   Session conversation memory for follow-up questions.
     * @param sink     Event consumer (typically WebSocket handler).
     */
    public void run(String question, long userId, AgentConversationMemory memory, Consumer<AgentEvent> sink) {
        run(question, userId, memory, null, null, sink);
    }

    /**
     * Runs one agent turn with session ID for observability tracing.
     *
     * @param sessionId WebSocket session identifier (may be null).
     */
    public void run(String question, long userId, AgentConversationMemory memory, String sessionId,
                    Consumer<AgentEvent> sink) {
        run(question, userId, memory, sessionId, null, sink);
    }

    /**
     * Runs one agent turn with session ID and page context for prompt assembly.
     *
     * @param sessionId   WebSocket session identifier (may be null).
     * @param pageContext Current page context sent by the client (may be null).
     */
    public void run(String question, long userId, AgentConversationMemory memory, String sessionId,
                    String pageContext, Consumer<AgentEvent> sink) {
        int maxSteps = Math.max(1, properties.getMaxSteps());
        AgentExecutionTrace trace = new AgentExecutionTrace(userId, sessionId, maxSteps);
        Observation agentSpan = agentObservationService.startAgentSpan(trace.getCorrelationId(), userId);
        try (Observation.Scope ignored = agentSpan.openScope()) {
            runInternal(question, userId, memory, sessionId, pageContext, sink, maxSteps, trace, agentSpan);
        } finally {
            agentObservationService.finishSpan(agentSpan, agentSpanAttributes(trace));
            trace.finish();
            trace.finishAndLog(objectMapper, agentMetrics);
            tracePersistenceService.persist(trace);
        }
    }

    private void runInternal(String question,
                             long userId,
                             AgentConversationMemory memory,
                             String sessionId,
                             String pageContext,
                             Consumer<AgentEvent> sink,
                             int maxSteps,
                             AgentExecutionTrace trace,
                             Observation agentSpan) {
        try {
            if (question == null || question.isBlank()) {
                trace.terminateError();
                trace.setErrorMessage(agentDomainConfig.errors().questionEmpty());
                emitError(sink, agentDomainConfig.errors().questionEmpty());
                return;
            }

            Map<String, AgentTool> toolMap = new LinkedHashMap<>();
            for (AgentTool tool : tools) {
                toolMap.put(tool.name(), tool);
            }

            String historyBlock = memory == null ? "" : memory.formatForPrompt();
            String system = contextEngine.buildSystemPrompt(
                    userId,
                    sessionId,
                    pageContext,
                    toolMap.values(),
                    historyBlock,
                    question.trim());
            List<String> transcript = new ArrayList<>();
            if (!historyBlock.isBlank()) {
                transcript.add(historyBlock);
            }
            transcript.add(agentDomainConfig.context().currentQuestionHeading()
                    + "\n" + agentDomainConfig.context().userLabel() + " " + question.trim());

            for (int step = 0; step < maxSteps; step++) {
                String userPrompt = String.join("\n\n", transcript);
                int inputChars = system.length() + userPrompt.length();
                Observation llmSpan = agentObservationService.startLlmSpan(agentSpan, properties.getModel());
                long stepLlmStart = System.currentTimeMillis();
                String llmOut;
                try {
                    llmOut = llmInvoker.call(system, userPrompt, 0.1, 1024);
                } catch (TimeoutException e) {
                    long stepLlmMs = System.currentTimeMillis() - stepLlmStart;
                    agentObservationService.finishSpanError(llmSpan, "llm_timeout",
                            llmSpanAttributes(stepLlmMs, inputChars, 0, "timeout"));
                    log.warn("Agent LLM call timed out (>{}s)", properties.getLlmTimeoutSeconds());
                    trace.terminateTimeout();
                    trace.setErrorMessage(agentDomainConfig.errors().modelResponseTimeout());
                    emitError(sink, agentDomainConfig.errors().modelResponseTimeout());
                    return;
                } catch (Exception e) {
                    long stepLlmMs = System.currentTimeMillis() - stepLlmStart;
                    agentObservationService.finishSpanError(llmSpan, "llm_error",
                            llmSpanAttributes(stepLlmMs, inputChars, 0, "error"));
                    log.warn("Agent LLM call failed: {}", e.getMessage());
                    trace.terminateError();
                    trace.setErrorMessage(agentDomainConfig.errors().modelCallFailed());
                    emitError(sink, agentDomainConfig.errors().modelCallFailed());
                    return;
                }
                long stepLlmMs = System.currentTimeMillis() - stepLlmStart;
                trace.recordLlmCall(stepLlmMs, inputChars, llmOut.length());
                agentObservationService.finishSpan(llmSpan,
                        llmSpanAttributes(stepLlmMs, inputChars, llmOut.length(), "ok"));

                AgentAction action;
                try {
                    action = parseAction(llmOut);
                } catch (Exception e) {
                    log.warn("Agent JSON parse failed (step {}): {}", step + 1, abbreviate(llmOut, 240));
                    String observation = agentDomainConfig.systemPrompt().parseErrorObservation();
                    ObjectNode thinkData = objectMapper.createObjectNode();
                    thinkData.put("content", agentDomainConfig.systemPrompt().parseErrorThink());
                    AgentEvent.send(sink, "think", thinkData);
                    emitObserve(sink, observation);
                    transcript.add(agentDomainConfig.context().assistantLabel() + " " + llmOut);
                    transcript.add(agentDomainConfig.context().observationLabel() + " " + observation);
                    trace.recordStep(step, "parse_error", stepLlmMs, 0);
                    continue;
                }

                emitThink(sink, action);

                if (action.isFinal()) {
                    long streamLlmMs = streamFinalAnswer(sink, question, transcript, memory, trace, agentSpan);
                    trace.recordStep(step, "final_answer", stepLlmMs + streamLlmMs, 0);
                    return;
                }

                AgentTool tool = toolMap.get(action.action());
                if (tool == null) {
                    String observation = agentDomainConfig.render(
                            agentDomainConfig.errors().unknownTool(),
                            "toolName", action.action());
                    emitAct(sink, action.action(), action.input());
                    emitObserve(sink, observation);
                    transcript.add(agentDomainConfig.context().assistantLabel() + " " + llmOut);
                    transcript.add(agentDomainConfig.context().observationLabel() + " " + observation);
                    trace.recordStep(step, action.action(), stepLlmMs, 0);
                    continue;
                }

                Map<String, Object> inputMap = new LinkedHashMap<>(jsonToMap(action.input()));
                inputMap.put("_userQuestion", question.trim());
                if (memory != null) {
                    inputMap.put("_conversationHistory", memory.formatForPrompt());
                }
                emitAct(sink, tool.name(), action.input());
                Observation toolSpan = agentObservationService.startToolSpan(agentSpan, tool.name());
                long toolStart = System.currentTimeMillis();
                AgentToolResult toolResult = agentToolExecutor.execute(tool, inputMap, userId);
                String observation = toolResult.observation();
                long stepToolMs = System.currentTimeMillis() - toolStart;
                agentObservationService.finishSpan(toolSpan, toolSpanAttributes(
                        tool.name(), stepToolMs, toolResult.status() == AgentToolResult.Status.TIMEOUT));
                trace.recordToolCall(tool.name(), stepToolMs, summarizeToolInput(action.input()), observation);
                observation = augmentObservation(tool.name(), observation, toolResult.status());
                emitObserve(sink, observation);
                transcript.add(agentDomainConfig.context().assistantLabel() + " " + llmOut);
                transcript.add(agentDomainConfig.context().observationLabel() + " " + observation);
                trace.recordStep(step, tool.name(), stepLlmMs, stepToolMs);
            }

            trace.terminateMaxSteps();
            String maxStepsMessage = agentDomainConfig.render(
                    agentDomainConfig.errors().maxSteps(),
                    "maxSteps", maxSteps);
            trace.setErrorMessage(maxStepsMessage);
            emitError(sink, maxStepsMessage);
        } catch (RuntimeException e) {
            trace.terminateError();
            trace.setErrorMessage(e.getMessage());
            throw e;
        }
    }

    /**
     * Streams the final natural-language answer to the client and persists turn to memory.
     *
     * @return LLM streaming duration in milliseconds.
     */
    private long streamFinalAnswer(
            Consumer<AgentEvent> sink,
            String question,
            List<String> transcript,
            AgentConversationMemory memory,
            AgentExecutionTrace trace,
            Observation agentSpan) {
        String context = String.join("\n\n", transcript);
        String system = agentDomainConfig.render(
                agentDomainConfig.systemPrompt().finalAnswerSystem(),
                "soul", characterSoulService.getSoulContent());
        String userPrompt = context + "\n\n" + agentDomainConfig.systemPrompt().finalAnswerPrompt();
        int inputChars = system.length() + userPrompt.length();

        int timeoutSec = Math.max(1, properties.getLlmTimeoutSeconds());
        Observation llmSpan = agentObservationService.startLlmSpan(agentSpan, properties.getModel());
        long streamStart = System.currentTimeMillis();
        AtomicLong firstTokenMs = new AtomicLong(-1);
        try {
            Flux<String> flux = llmInvoker.stream(system, userPrompt, 0.3, 1024);

            StringBuilder full = new StringBuilder();
            flux.doOnNext(chunk -> {
                if (chunk != null && !chunk.isEmpty()) {
                    firstTokenMs.compareAndSet(-1, System.currentTimeMillis() - trace.getStartedAtMs());
                    full.append(chunk);
                    emitThrottledDelta(sink, chunk);
                }
            }).blockLast();

            String answer = truncateAnswer(full.toString());
            long streamMs = System.currentTimeMillis() - streamStart;
            Long ttft = firstTokenMs.get() >= 0 ? firstTokenMs.get() : null;
            trace.recordLlmCall(streamMs, inputChars, answer.length(), ttft);
            agentObservationService.finishSpan(llmSpan, llmSpanAttributes(
                    streamMs, inputChars, answer.length(), "ok"));
            trace.terminateFinalAnswer(answer);
            emitFinal(sink, answer);
            if (memory != null && !answer.isBlank()) {
                memory.add(AgentTurn.user(question.trim()));
                memory.add(AgentTurn.assistant(answer));
            }
            return streamMs;
        } catch (Exception e) {
            long streamMs = System.currentTimeMillis() - streamStart;
            Long ttft = firstTokenMs.get() >= 0 ? firstTokenMs.get() : null;
            trace.recordLlmCall(streamMs, inputChars, 0, ttft);
            if (isTimeout(e)) {
                agentObservationService.finishSpanError(llmSpan, "stream_timeout",
                        llmSpanAttributes(streamMs, inputChars, 0, "timeout"));
                log.warn("Agent streaming answer timed out (>{}s)", timeoutSec);
                trace.terminateTimeout();
                trace.setErrorMessage(agentDomainConfig.errors().responseTimeout());
                emitError(sink, agentDomainConfig.errors().responseTimeout());
                return streamMs;
            }
            agentObservationService.finishSpanError(llmSpan, "stream_error",
                    llmSpanAttributes(streamMs, inputChars, 0, "error"));
            log.warn("Agent streaming answer failed: {}", e.getMessage());
            trace.terminateError();
            trace.setErrorMessage(agentDomainConfig.errors().responseFailed());
            emitError(sink, agentDomainConfig.errors().responseFailed());
            return streamMs;
        }
    }

    private String truncateAnswer(String answer) {
        if (answer == null || answer.isEmpty()) {
            return "";
        }
        int max = Math.max(1, properties.getMaxResponseChars());
        if (answer.length() <= max) {
            return answer;
        }
        return answer.substring(0, max);
    }

    private static boolean isTimeout(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof TimeoutException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && msg.toLowerCase().contains("timeout")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String abbreviate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "...";
    }

    /** Emits delta chunks character-by-character for typewriter UX on WebSocket clients. */
    private void emitThrottledDelta(Consumer<AgentEvent> sink, String chunk) {
        int delayMs = Math.max(0, properties.getStreamCharDelayMs());
        if (delayMs == 0) {
            emitDelta(sink, chunk);
            return;
        }
        int i = 0;
        while (i < chunk.length()) {
            int cp = chunk.codePointAt(i);
            emitDelta(sink, new String(Character.toChars(cp)));
            i += Character.charCount(cp);
            if (i < chunk.length()) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private boolean isSiteTool(String toolName) {
        return "fulltext_search".equals(toolName) || "article_rag".equals(toolName);
    }

    private boolean isBangumiTool(String toolName) {
        return toolName != null && toolName.startsWith("bangumi_");
    }

    private boolean isEmptySiteResult(String observation) {
        if (observation == null) {
            return true;
        }
        return agentDomainConfig.systemPrompt().emptySiteResultMarkers()
                .stream()
                .anyMatch(observation::contains);
    }

    /** Adds gentle follow-up guidance to Observation for the next ReAct step. */
    private String augmentObservation(
            String toolName,
            String observation,
            AgentToolResult.Status toolStatus) {
        String result = observation == null ? "" : observation;
        if (isEmptySiteResult(result) && isSiteTool(toolName)) {
            result = result + "\n\n" + agentDomainConfig.systemPrompt().siteEmptyGuidance();
        }
        if (toolStatus == AgentToolResult.Status.TIMEOUT && isBangumiTool(toolName)) {
            result = result + "\n\n" + agentDomainConfig.systemPrompt().bangumiTimeoutGuidance();
        }
        return result;
    }

    private String summarizeToolInput(JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return "";
        }
        try {
            String json = objectMapper.writeValueAsString(input);
            return json.length() <= 256 ? json : json.substring(0, 256);
        } catch (Exception e) {
            return input.toString();
        }
    }

    private AgentAction parseAction(String llmOut) throws Exception {
        String json = jsonExtractor.extractActionJson(llmOut);
        JsonNode node = objectMapper.readTree(json);
        String action = node.path("action").asText(null);
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("missing action");
        }
        JsonNode input = node.path("input");
        String answer = node.path("answer").asText(null);
        return new AgentAction(action, input.isMissingNode() ? null : input, answer);
    }

    private Map<String, Object> jsonToMap(JsonNode input) {
        if (input == null || input.isNull() || input.isMissingNode()) {
            return Map.of();
        }
        return objectMapper.convertValue(input, Map.class);
    }

    private void emitThink(Consumer<AgentEvent> sink, AgentAction action) {
        ObjectNode data = objectMapper.createObjectNode();
        if (action.isFinal()) {
            data.put("content", agentDomainConfig.systemPrompt().finalThinking());
        } else {
            data.put("content", agentDomainConfig.render(
                    agentDomainConfig.systemPrompt().toolThinking(),
                    "toolName", action.action()));
            if (action.input() != null && !action.input().isMissingNode()) {
                data.set("input", action.input());
            }
        }
        AgentEvent.send(sink, "think", data);
    }

    private void emitAct(Consumer<AgentEvent> sink, String tool, JsonNode input) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("tool", tool);
        data.set("input", input == null ? objectMapper.createObjectNode() : input);
        AgentEvent.send(sink, "act", data);
    }

    private void emitObserve(Consumer<AgentEvent> sink, String content) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("content", content);
        AgentEvent.send(sink, "observe", data);
    }

    private void emitDelta(Consumer<AgentEvent> sink, String content) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("content", content);
        AgentEvent.send(sink, "delta", data);
    }

    private void emitFinal(Consumer<AgentEvent> sink, String content) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("content", content);
        AgentEvent.send(sink, "final", data);
    }

    private void emitError(Consumer<AgentEvent> sink, String message) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("message", message);
        AgentEvent.send(sink, "error", data);
    }

    private static Map<String, String> agentSpanAttributes(AgentExecutionTrace trace) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("agent.status", trace.getTerminatedBy());
        attrs.put("agent.total_steps", String.valueOf(trace.getTotalSteps()));
        attrs.put("agent.llm_calls", String.valueOf(trace.getLlmCalls()));
        attrs.put("agent.duration_ms", String.valueOf(
                trace.getDurationMs() == null ? 0 : trace.getDurationMs()));
        return attrs;
    }

    private static Map<String, String> llmSpanAttributes(long durationMs, int inputChars, int outputChars, String status) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("llm.duration_ms", String.valueOf(durationMs));
        attrs.put("llm.input_chars", String.valueOf(inputChars));
        attrs.put("llm.output_chars", String.valueOf(outputChars));
        attrs.put("llm.status", status);
        return attrs;
    }

    private static Map<String, String> toolSpanAttributes(String toolName, long durationMs, boolean timedOut) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("tool.name", toolName);
        attrs.put("tool.duration_ms", String.valueOf(durationMs));
        attrs.put("tool.success", String.valueOf(!timedOut));
        return attrs;
    }
}
