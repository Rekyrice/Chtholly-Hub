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

        for (int step = 0; step < request.maxSteps(); step++) {
            String userPrompt = String.join("\n\n", transcript);
            int inputChars = request.systemPrompt().length() + userPrompt.length();
            Observation llmSpan = agentObservationService.startLlmSpan(agentSpan, llmInvoker.modelName());
            long stepLlmStart = System.currentTimeMillis();
            String llmOut;
            try {
                llmOut = llmInvoker.call(request.systemPrompt(), userPrompt, 0.1, 1024);
            } catch (TimeoutException e) {
                long stepLlmMs = System.currentTimeMillis() - stepLlmStart;
                agentObservationService.finishSpanError(llmSpan, "llm_timeout",
                        AgentSpanAttributes.llm(stepLlmMs, inputChars, 0, "timeout"));
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
                agentObservationService.finishSpanError(llmSpan, "llm_error",
                        AgentSpanAttributes.llm(stepLlmMs, inputChars, 0, "error"));
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
            trace.recordLlmCall(stepLlmMs, inputChars, llmOut.length());
            agentObservationService.finishSpan(llmSpan,
                    AgentSpanAttributes.llm(stepLlmMs, inputChars, llmOut.length(), "ok"));

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
                trace.recordStep(step, action.action(), stepLlmMs, 0);
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
            AgentToolResult toolResult = agentToolExecutor.execute(tool, inputMap, request.userId());
            String observation = toolResult.observation();
            long stepToolMs = System.currentTimeMillis() - toolStart;
            finishToolSpan(toolSpan, tool.name(), stepToolMs, toolResult.status());
            trace.recordToolCall(
                    tool.name(),
                    stepToolMs,
                    summarizeToolInput(action.input()),
                    observation,
                    toolResult.status() == AgentToolResult.Status.SUCCESS);
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
            String toolName,
            long durationMs,
            AgentToolResult.Status status) {
        Map<String, String> attributes = AgentSpanAttributes.tool(toolName, durationMs, status);
        switch (status) {
            case TIMEOUT -> agentObservationService.finishSpanError(toolSpan, "tool_timeout", attributes);
            case ERROR -> agentObservationService.finishSpanError(toolSpan, "tool_error", attributes);
            case INTERRUPTED -> agentObservationService.finishSpanError(toolSpan, "tool_interrupted", attributes);
            default -> agentObservationService.finishSpan(toolSpan, attributes);
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
