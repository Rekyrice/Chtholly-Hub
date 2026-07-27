package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentAction;
import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.AgentJsonExtractor;
import com.chtholly.agent.AgentTool;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentObservationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.Observation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Executes the bounded Think-Act-Observe portion of one agent turn. */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AgentLoopExecutor {

    private final AgentLlmInvoker llmInvoker;
    private final AgentToolExecutor agentToolExecutor;
    private final AgentJsonExtractor jsonExtractor;
    private final ObjectMapper objectMapper;
    private final AgentObservationService agentObservationService;
    private final AgentDomainConfig agentDomainConfig;

    /**
     * Executes model decisions and at most one tool per step until a terminal outcome.
     *
     * @param request immutable loop inputs
     * @param trace mutable execution trace owned by the caller
     * @param agentSpan parent observation span owned by the caller
     * @param sink event consumer
     * @return terminal status and accumulated transcript
     */
    public AgentLoopResult execute(
            AgentLoopRequest request,
            AgentExecutionTrace trace,
            Observation agentSpan,
            Consumer<AgentEvent> sink) {
        List<String> transcript = initialTranscript(request);
        boolean charactersRequired = requiresBangumiCharacters(request);
        boolean bangumiSearchCompleted = false;
        boolean bangumiCharactersCompleted = false;

        for (int step = 0; step < request.maxSteps(); step++) {
            String userPrompt = String.join("\n\n", transcript);
            int inputChars = request.systemPrompt().length() + userPrompt.length();
            Observation llmSpan = agentObservationService.startLlmSpan(agentSpan, llmInvoker.modelName());
            long stepLlmStart = System.currentTimeMillis();
            String llmOut;
            try {
                llmOut = invokeDecisionWithRetry(
                        request.systemPrompt(), userPrompt, inputChars, step, trace);
            } catch (InterruptedException e) {
                long stepLlmMs = System.currentTimeMillis() - stepLlmStart;
                Thread.currentThread().interrupt();
                agentObservationService.finishSpanError(
                        llmSpan,
                        "llm_interrupted",
                        AgentSpanAttributes.llm("interrupted"),
                        Map.of());
                log.warn("Agent LLM call interrupted", e);
                return terminate(
                        AgentLoopResult.Status.LLM_INTERRUPTED,
                        transcript,
                        agentDomainConfig.errors().modelCallInterrupted(),
                        trace,
                        sink,
                        trace::terminateError);
            } catch (TimeoutException e) {
                long stepLlmMs = System.currentTimeMillis() - stepLlmStart;
                agentObservationService.finishSpanError(
                        llmSpan,
                        "llm_timeout",
                        AgentSpanAttributes.llm("timeout"),
                        Map.of());
                log.warn("Agent LLM call timed out (>{}s)", llmInvoker.timeoutSeconds());
                return terminate(
                        AgentLoopResult.Status.LLM_TIMEOUT,
                        transcript,
                        agentDomainConfig.errors().modelResponseTimeout(),
                        trace,
                        sink,
                        trace::terminateTimeout);
            } catch (Exception e) {
                long stepLlmMs = System.currentTimeMillis() - stepLlmStart;
                agentObservationService.finishSpanError(
                        llmSpan,
                        "llm_error",
                        AgentSpanAttributes.llm("error"),
                        Map.of());
                log.warn("Agent LLM call failed: {}", e.getMessage());
                return terminate(
                        AgentLoopResult.Status.LLM_ERROR,
                        transcript,
                        agentDomainConfig.errors().modelCallFailed(),
                        trace,
                        sink,
                        trace::terminateError);
            }

            long stepLlmMs = System.currentTimeMillis() - stepLlmStart;
            agentObservationService.finishSpan(
                    llmSpan,
                    AgentSpanAttributes.llm("ok"),
                    Map.of());

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
                appendExchange(transcript, llmOut, observation);
                trace.recordStep(step, "parse_error", stepLlmMs, 0);
                continue;
            }

            emitThink(sink, action);
            if (action.isFinal()) {
                if (charactersRequired && bangumiSearchCompleted && !bangumiCharactersCompleted) {
                    String observation = "COMPOUND_QUERY_INCOMPLETE：作品资料已查询，但角色问题尚未完成。"
                            + "请继续调用 bangumi_characters，再生成最终回答。";
                    emitObserve(sink, observation);
                    appendExchange(transcript, llmOut, observation);
                    trace.recordStep(step, "compound_tool_pending", stepLlmMs, 0);
                    continue;
                }
                return AgentLoopResult.finalReady(transcript, step, stepLlmMs);
            }

            AgentTool tool = request.tools().get(action.action());
            if (tool == null) {
                String observation = agentDomainConfig.render(
                        agentDomainConfig.errors().unknownTool(),
                        "toolName", action.action());
                emitAct(sink, action.action(), action.input());
                emitObserve(sink, observation);
                appendExchange(transcript, llmOut, observation);
                trace.recordStep(step, "unknown_tool", stepLlmMs, 0);
                continue;
            }

            Map<String, Object> inputMap = new LinkedHashMap<>(jsonToMap(action.input()));
            inputMap.put("_userQuestion", request.question());
            if (!request.historyBlock().isBlank()) {
                inputMap.put("_conversationHistory", request.historyBlock());
            }
            emitAct(sink, tool.name(), action.input());
            Observation toolSpan = agentObservationService.startToolSpan(agentSpan, tool.name());
            long toolStart = System.currentTimeMillis();
            AgentToolResult toolResult;
            try {
                toolResult = agentToolExecutor.execute(tool, inputMap, request.userId());
            } catch (RuntimeException e) {
                agentObservationService.finishSpanError(
                        toolSpan,
                        "tool_executor_error",
                        Map.of("status", "error", "error.type", "INTERNAL_ERROR"),
                        Map.of());
                throw e;
            }
            String observation = toolResult.observation();
            long stepToolMs = System.currentTimeMillis() - toolStart;
            finishToolSpan(toolSpan, toolResult.status());
            trace.recordToolCall(
                    step,
                    tool.name(),
                    stepToolMs,
                    summarizeToolInput(action.input()),
                    observation,
                    toolResult.status() == AgentToolResult.Status.SUCCESS);
            if ("bangumi_search".equals(tool.name())
                    && toolResult.status() == AgentToolResult.Status.SUCCESS) {
                bangumiSearchCompleted = true;
            }
            if ("bangumi_characters".equals(tool.name())) {
                bangumiCharactersCompleted = true;
            }
            observation = augmentObservation(tool.name(), observation, toolResult.status());
            emitObserve(sink, observation);
            trace.recordStep(step, tool.name(), stepLlmMs, stepToolMs);
            if (toolResult.status() == AgentToolResult.Status.INTERRUPTED) {
                return terminate(
                        AgentLoopResult.Status.TOOL_INTERRUPTED,
                        transcript,
                        agentDomainConfig.errors().toolInterrupted(),
                        trace,
                        sink,
                        trace::terminateError);
            }
            appendExchange(transcript, llmOut, observation);
        }

        String maxStepsMessage = agentDomainConfig.render(
                agentDomainConfig.errors().maxSteps(),
                "maxSteps", request.maxSteps());
        return terminate(
                AgentLoopResult.Status.MAX_STEPS,
                transcript,
                maxStepsMessage,
                trace,
                sink,
                trace::terminateMaxSteps);
    }

    private String invokeDecisionWithRetry(
            String systemPrompt,
            String userPrompt,
            int inputChars,
            int step,
            AgentExecutionTrace trace) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            long attemptStartedAt = System.currentTimeMillis();
            try {
                String output = llmInvoker.call(systemPrompt, userPrompt, 0.1, 1024);
                trace.recordLlmCall(
                        step,
                        System.currentTimeMillis() - attemptStartedAt,
                        inputChars,
                        output == null ? 0 : output.length(),
                        null);
                return output;
            } catch (Exception exception) {
                trace.recordLlmCall(
                        step,
                        System.currentTimeMillis() - attemptStartedAt,
                        inputChars,
                        0,
                        null);
                if (attempt == 0 && isRetryableModelFailure(exception)) {
                    log.warn(
                            "Agent LLM call failed transiently; retrying once: {}",
                            exception.getMessage());
                    continue;
                }
                throw exception;
            }
        }
        throw new IllegalStateException("unreachable model retry state");
    }

    private boolean isRetryableModelFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage() == null
                    ? ""
                    : current.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (className.contains("TransientAiException")
                    || className.contains("ResourceAccessException")
                    || className.contains("ConnectException")
                    || className.contains("SocketException")
                    || message.contains("429")
                    || message.contains("rate limit")
                    || message.contains("too many requests")
                    || message.contains("temporarily unavailable")
                    || message.contains("connection reset")
                    || message.contains("connection refused")) {
                return true;
            }
            if (current instanceof TimeoutException || current instanceof InterruptedException) {
                return false;
            }
            current = current.getCause();
        }
        return false;
    }

    private List<String> initialTranscript(AgentLoopRequest request) {
        List<String> transcript = new ArrayList<>();
        if (!request.historyBlock().isBlank()) {
            transcript.add(request.historyBlock());
        }
        transcript.add(agentDomainConfig.context().currentQuestionHeading()
                + "\n" + agentDomainConfig.context().userLabel() + " " + request.question());
        return transcript;
    }

    private AgentLoopResult terminate(
            AgentLoopResult.Status status,
            List<String> transcript,
            String message,
            AgentExecutionTrace trace,
            Consumer<AgentEvent> sink,
            Runnable traceTerminator) {
        traceTerminator.run();
        trace.setErrorMessage(message);
        emitError(sink, message);
        return AgentLoopResult.terminal(status, transcript, message);
    }

    private void appendExchange(List<String> transcript, String llmOut, String observation) {
        transcript.add(agentDomainConfig.context().assistantLabel() + " " + llmOut);
        transcript.add(agentDomainConfig.context().observationLabel() + " " + observation);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonToMap(JsonNode input) {
        if (input == null || input.isNull() || input.isMissingNode()) {
            return Map.of();
        }
        return objectMapper.convertValue(input, Map.class);
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

    private boolean isSiteTool(String toolName) {
        return "fulltext_search".equals(toolName) || "article_rag".equals(toolName);
    }

    private boolean isBangumiTool(String toolName) {
        return toolName != null && toolName.startsWith("bangumi_");
    }

    private boolean requiresBangumiCharacters(AgentLoopRequest request) {
        if (!request.tools().containsKey("bangumi_characters")) {
            return false;
        }
        String question = request.question() == null ? "" : request.question().toLowerCase();
        return question.contains("角色")
                || question.contains("人物")
                || question.contains("登场")
                || question.contains("配角")
                || question.contains("character");
    }

    private boolean isEmptySiteResult(String observation) {
        if (observation == null) {
            return true;
        }
        return agentDomainConfig.systemPrompt().emptySiteResultMarkers()
                .stream()
                .anyMatch(observation::contains);
    }

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

    private void emitError(Consumer<AgentEvent> sink, String message) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("message", message);
        AgentEvent.send(sink, "error", data);
    }

    private void finishToolSpan(
            Observation toolSpan,
            AgentToolResult.Status status) {
        Map<String, String> attributes = AgentSpanAttributes.tool(status);
        switch (status) {
            case TIMEOUT -> agentObservationService.finishSpanError(
                    toolSpan, "tool_timeout", attributes, Map.of());
            case ERROR -> agentObservationService.finishSpanError(
                    toolSpan, "tool_error", attributes, Map.of());
            case INTERRUPTED -> agentObservationService.finishSpanError(
                    toolSpan, "tool_interrupted", attributes, Map.of());
            default -> agentObservationService.finishSpan(toolSpan, attributes, Map.of());
        }
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
}
