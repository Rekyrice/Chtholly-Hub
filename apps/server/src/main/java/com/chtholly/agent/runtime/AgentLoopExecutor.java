package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentAction;
import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.AgentJsonExtractor;
import com.chtholly.agent.AgentTool;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.evidence.Evidence;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.observability.AgentToolDiagnostics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.Observation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Executes the bounded Think-Act-Observe portion of one agent turn. */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AgentLoopExecutor {

    private static final String WEB_RESEARCH_INCOMPLETE =
            "WEB_RESEARCH_INCOMPLETE: web_search results are discovery hints, not evidence. "
                    + "Call web_fetch for at least one result and use only returned Evidence.";

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
        EvidenceState evidenceState = new EvidenceState(
                request.evidenceSet(), request.evidenceRequired());
        boolean charactersRequired = requiresBangumiCharacters(request);
        boolean bangumiSearchCompleted = false;
        boolean bangumiCharactersCompleted = false;

        for (int step = 0; step < request.maxSteps(); step++) {
            AgentLoopResult unavailable = stopIfTurnUnavailable(
                    request, trace, sink, transcript, evidenceState);
            if (unavailable != null) {
                return unavailable;
            }
            String userPrompt = String.join("\n\n", transcript);
            int inputChars = request.systemPrompt().length() + userPrompt.length();
            Observation llmSpan = agentObservationService.startLlmSpan(agentSpan, llmInvoker.modelName());
            long stepLlmStart = System.currentTimeMillis();
            String llmOut;
            try {
                llmOut = invokeDecisionWithRetry(
                        request.systemPrompt(), userPrompt, inputChars, step, trace,
                        request.turnBudget());
            } catch (AgentTurnBudget.UnavailableException exception) {
                agentObservationService.finishSpanError(
                        llmSpan,
                        exception.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                                ? "turn_cancelled"
                                : "turn_timeout",
                        AgentSpanAttributes.llm(exception.reason().name().toLowerCase()),
                        Map.of());
                return terminateUnavailable(exception, transcript, trace, sink, evidenceState);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (request.turnBudget() != null && request.turnBudget().isCancelled()) {
                    agentObservationService.finishSpanError(
                            llmSpan,
                            "turn_cancelled",
                            AgentSpanAttributes.llm("cancelled"),
                            Map.of());
                    return terminateUnavailable(
                            AgentTurnBudget.unavailableForStage(
                                    AgentTurnBudget.UnavailableReason.CANCELLED,
                                    "loop_llm"),
                            transcript,
                            trace,
                            sink,
                            evidenceState);
                }
                agentObservationService.finishSpanError(
                        llmSpan,
                        "llm_interrupted",
                        AgentSpanAttributes.llm("interrupted"),
                        Map.of());
                log.warn("Agent LLM call interrupted: {}", e.getClass().getName());
                return terminate(
                        AgentLoopResult.Status.LLM_INTERRUPTED,
                        transcript,
                        agentDomainConfig.errors().modelCallInterrupted(),
                        trace,
                        sink,
                        trace::terminateError,
                        evidenceState);
            } catch (TimeoutException e) {
                long stepLlmMs = System.currentTimeMillis() - stepLlmStart;
                agentObservationService.finishSpanError(
                        llmSpan,
                        "llm_timeout",
                        AgentSpanAttributes.llm("timeout"),
                        Map.of());
                log.warn("Agent LLM call timed out (>{}s)", llmInvoker.timeoutSeconds());
                if (request.turnBudget() != null && request.turnBudget().isExpired()) {
                    return terminateUnavailable(
                            AgentTurnBudget.unavailableForStage(
                                    AgentTurnBudget.UnavailableReason.TIMEOUT, "loop_llm"),
                            transcript,
                            trace,
                            sink,
                            evidenceState);
                }
                return terminate(
                        AgentLoopResult.Status.LLM_TIMEOUT,
                        transcript,
                        agentDomainConfig.errors().modelResponseTimeout(),
                        trace,
                        sink,
                        trace::terminateTimeout,
                        evidenceState);
            } catch (Exception e) {
                long stepLlmMs = System.currentTimeMillis() - stepLlmStart;
                agentObservationService.finishSpanError(
                        llmSpan,
                        "llm_error",
                        AgentSpanAttributes.llm("error"),
                        Map.of());
                log.warn("Agent LLM call failed: {}", e.getClass().getName());
                return terminate(
                        AgentLoopResult.Status.LLM_ERROR,
                        transcript,
                        agentDomainConfig.errors().modelCallFailed(),
                        trace,
                        sink,
                        trace::terminateError,
                        evidenceState);
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
                if (evidenceState.webFetchPending()) {
                    emitObserve(sink, WEB_RESEARCH_INCOMPLETE);
                    appendExchange(transcript, llmOut, WEB_RESEARCH_INCOMPLETE);
                    trace.recordStep(step, "web_fetch_pending", stepLlmMs, 0);
                    continue;
                }
                return AgentLoopResult.finalReady(
                        evidenceState.transcriptForFinalAnswer(transcript),
                        step,
                        stepLlmMs,
                        evidenceState.evidenceSet(),
                        evidenceState.evidenceRequired());
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
            String actualToolInput = serializeToolInput(inputMap);
            emitAct(sink, tool.name(), action.input());
            Observation toolSpan = agentObservationService.startToolSpan(agentSpan, tool.name());
            long toolStart = System.currentTimeMillis();
            long toolBudgetBeforeMs = remainingBudgetMs(request.turnBudget());
            AgentToolResult toolResult;
            try {
                toolResult = request.turnBudget() == null
                        ? agentToolExecutor.execute(tool, inputMap, request.userId())
                        : agentToolExecutor.execute(
                                tool,
                                inputMap,
                                request.userId(),
                                request.turnBudget().remaining(
                                        "loop_tool", request.turnBudget().totalBudget()));
            } catch (AgentTurnBudget.UnavailableException exception) {
                long toolDurationMs = elapsedMillis(toolStart);
                long toolBudgetAfterMs = remainingBudgetMs(request.turnBudget());
                AgentToolResult failureResult = safeToolFailureResult(
                        tool,
                        inputMap,
                        exception.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                                ? AgentToolResult.Status.INTERRUPTED
                                : AgentToolResult.Status.TIMEOUT,
                        exception.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                                ? "TURN_CANCELLED"
                                : "TURN_TIMEOUT");
                trace.recordToolCall(
                        step,
                        tool.name(),
                        toolDurationMs,
                        toolBudgetBeforeMs,
                        toolBudgetAfterMs,
                        failureResult,
                        actualToolInput,
                        null);
                agentObservationService.finishSpanError(
                        toolSpan,
                        exception.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                                ? "turn_cancelled"
                                : "turn_timeout",
                        Map.of("status", exception.reason().name().toLowerCase()),
                        Map.of());
                return terminateUnavailable(exception, transcript, trace, sink, evidenceState);
            } catch (RuntimeException e) {
                long toolDurationMs = elapsedMillis(toolStart);
                long toolBudgetAfterMs = remainingBudgetMs(request.turnBudget());
                trace.recordToolCall(
                        step,
                        tool.name(),
                        toolDurationMs,
                        toolBudgetBeforeMs,
                        toolBudgetAfterMs,
                        safeToolFailureResult(
                                tool,
                                inputMap,
                                AgentToolResult.Status.ERROR,
                                "TOOL_EXECUTOR_ERROR"),
                        actualToolInput,
                        null);
                agentObservationService.finishSpanError(
                        toolSpan,
                        "tool_executor_error",
                        Map.of("status", "error", "error.type", "INTERNAL_ERROR"),
                        Map.of());
                throw e;
            }
            String observation = toolResult.observation();
            long stepToolMs = System.currentTimeMillis() - toolStart;
            long toolBudgetAfterMs = remainingBudgetMs(request.turnBudget());
            finishToolSpan(toolSpan, toolResult.status());
            observation = augmentObservation(tool.name(), observation, toolResult.status());
            String canonicalObservation = observation;
            boolean observationContainsDynamicEvidence = false;
            if (toolResult.status() == AgentToolResult.Status.SUCCESS) {
                String dynamicEvidence = evidenceState.merge(toolResult.evidence());
                if (!dynamicEvidence.isBlank()) {
                    observationContainsDynamicEvidence = true;
                    observation = observation.isBlank()
                            ? dynamicEvidence
                            : observation + "\n\n" + dynamicEvidence;
                }
                if ("web_search".equals(tool.name())) {
                    evidenceState.recordSuccessfulWebSearch(
                            parseWebSearchDiscovery(toolResult.observation()));
                } else if ("web_fetch".equals(tool.name())) {
                    evidenceState.recordSuccessfulWebFetch(
                            parseWebFetchRequestedUrl(toolResult.observation()),
                            toolResult.evidence());
                }
            }
            trace.recordToolCall(
                    step,
                    tool.name(),
                    stepToolMs,
                    toolBudgetBeforeMs,
                    toolBudgetAfterMs,
                    normalizedToolResult(toolResult),
                    actualToolInput,
                    observation);
            if ("bangumi_search".equals(tool.name())
                    && toolResult.status() == AgentToolResult.Status.SUCCESS) {
                bangumiSearchCompleted = true;
            }
            if ("bangumi_characters".equals(tool.name())) {
                bangumiCharactersCompleted = true;
            }
            emitObserve(sink, observation);
            trace.recordStep(step, tool.name(), stepLlmMs, stepToolMs);
            if (request.turnBudget() != null
                    && (request.turnBudget().isCancelled() || request.turnBudget().isExpired())) {
                AgentTurnBudget.UnavailableReason reason = request.turnBudget().isCancelled()
                        ? AgentTurnBudget.UnavailableReason.CANCELLED
                        : AgentTurnBudget.UnavailableReason.TIMEOUT;
                return terminateUnavailable(
                        AgentTurnBudget.unavailableForStage(reason, "loop_tool"),
                        transcript,
                        trace,
                        sink,
                        evidenceState);
            }
            if (toolResult.status() == AgentToolResult.Status.INTERRUPTED) {
                return terminate(
                        AgentLoopResult.Status.TOOL_INTERRUPTED,
                        transcript,
                        agentDomainConfig.errors().toolInterrupted(),
                        trace,
                        sink,
                        trace::terminateError,
                        evidenceState);
            }
            appendExchange(transcript, llmOut, observation);
            if (observationContainsDynamicEvidence) {
                evidenceState.recordEvidenceObservation(
                        transcript.size() - 1,
                        agentDomainConfig.context().observationLabel() + " " + canonicalObservation);
            }
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
                trace::terminateMaxSteps,
                evidenceState);
    }

    private String invokeDecisionWithRetry(
            String systemPrompt,
            String userPrompt,
            int inputChars,
            int step,
            AgentExecutionTrace trace,
            AgentTurnBudget turnBudget) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            long attemptStartedAt = System.currentTimeMillis();
            long budgetBeforeMs = remainingBudgetMs(turnBudget);
            try {
                String output = turnBudget == null
                        ? llmInvoker.call(systemPrompt, userPrompt, 0.1, 1024)
                        : llmInvoker.call(
                                systemPrompt,
                                userPrompt,
                                0.1,
                                1024,
                                turnBudget.remaining(
                                        "loop_llm",
                                        Duration.ofSeconds(llmInvoker.timeoutSeconds())));
                trace.recordLlmCall(
                        step,
                        "LOOP_DECISION",
                        llmInvoker.modelName(),
                        "SUCCESS",
                        "",
                        attempt + 1,
                        budgetBeforeMs,
                        remainingBudgetMs(turnBudget),
                        elapsedMillis(attemptStartedAt),
                        inputChars,
                        output == null ? 0 : output.length(),
                        null,
                        AgentExecutionTrace.LlmExchange.success(systemPrompt, userPrompt, output));
                return output;
            } catch (Exception exception) {
                LlmFailure traceFailure = classifyLlmFailure(exception, turnBudget);
                trace.recordLlmCall(
                        step,
                        "LOOP_DECISION",
                        llmInvoker.modelName(),
                        traceFailure.status(),
                        traceFailure.errorCode(),
                        attempt + 1,
                        budgetBeforeMs,
                        remainingBudgetMs(turnBudget),
                        elapsedMillis(attemptStartedAt),
                        inputChars,
                        0,
                        null,
                        AgentExecutionTrace.LlmExchange.failure(
                                systemPrompt,
                                userPrompt,
                                "",
                                exception));
                Exception terminalFailure = terminalLlmFailure(exception);
                if (terminalFailure != null) {
                    throw terminalFailure;
                }
                if (attempt == 0 && isRetryableModelFailure(exception)) {
                    log.warn(
                            "Agent LLM call failed transiently; retrying once: {}",
                            exception.getClass().getName());
                    continue;
                }
                throw exception;
            }
        }
        throw new IllegalStateException("unreachable model retry state");
    }

    private boolean isRetryableModelFailure(Throwable failure) {
        List<Throwable> causes = causeChain(failure);
        for (Throwable current : causes) {
            if (current instanceof AgentTurnBudget.UnavailableException
                    || current instanceof InterruptedException
                    || current instanceof TimeoutException) {
                return false;
            }
        }
        for (Throwable current : causes) {
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
        }
        return false;
    }

    private LlmFailure classifyLlmFailure(Throwable failure, AgentTurnBudget turnBudget) {
        List<Throwable> causes = causeChain(failure);
        for (Throwable current : causes) {
            if (current instanceof AgentTurnBudget.UnavailableException unavailable) {
                return unavailable.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                        ? new LlmFailure("CANCELLED", "TURN_CANCELLED")
                        : new LlmFailure("TIMEOUT", "TURN_TIMEOUT");
            }
        }
        for (Throwable current : causes) {
            if (current instanceof InterruptedException) {
                return turnBudget != null && turnBudget.isCancelled()
                        ? new LlmFailure("CANCELLED", "TURN_CANCELLED")
                        : new LlmFailure("INTERRUPTED", "LLM_INTERRUPTED");
            }
        }
        for (Throwable current : causes) {
            if (current instanceof TimeoutException) {
                return turnBudget != null && turnBudget.isExpired()
                        ? new LlmFailure("TIMEOUT", "TURN_TIMEOUT")
                        : new LlmFailure("TIMEOUT", "LLM_TIMEOUT");
            }
        }
        return isRetryableModelFailure(failure)
                ? new LlmFailure("ERROR", "LLM_TRANSIENT_ERROR")
                : new LlmFailure("ERROR", "LLM_ERROR");
    }

    private Exception terminalLlmFailure(Throwable failure) {
        List<Throwable> causes = causeChain(failure);
        for (Throwable current : causes) {
            if (current instanceof AgentTurnBudget.UnavailableException unavailable) {
                return unavailable;
            }
        }
        for (Throwable current : causes) {
            if (current instanceof InterruptedException interrupted) {
                return interrupted;
            }
        }
        for (Throwable current : causes) {
            if (current instanceof TimeoutException timeout) {
                return timeout;
            }
        }
        return null;
    }

    private List<Throwable> causeChain(Throwable failure) {
        List<Throwable> causes = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && seen.add(current)) {
            causes.add(current);
            current = current.getCause();
        }
        return causes;
    }

    private AgentToolResult normalizedToolResult(AgentToolResult result) {
        if (result.errorCode() != null && !result.errorCode().isBlank()) {
            return result;
        }
        String errorCode = switch (result.status()) {
            case SUCCESS -> "";
            case VALIDATION_ERROR -> "TOOL_VALIDATION_ERROR";
            case TIMEOUT -> "TOOL_TIMEOUT";
            case ERROR -> "TOOL_EXECUTION_ERROR";
            case INTERRUPTED -> "TOOL_INTERRUPTED";
        };
        return new AgentToolResult(
                result.observation(),
                result.status(),
                errorCode,
                result.diagnostics().withErrorCode(errorCode),
                result.evidence());
    }

    private AgentToolResult safeToolFailureResult(
            AgentTool tool,
            Map<String, Object> input,
            AgentToolResult.Status status,
            String errorCode) {
        AgentToolDiagnostics diagnostics;
        String operation = "unknown";
        try {
            operation = tool == null || tool.name() == null ? "unknown" : tool.name();
            diagnostics = AgentToolDiagnostics.standard(
                    operation,
                    tool == null ? Map.of() : tool.parameterSchema(),
                    input,
                    "");
        } catch (RuntimeException diagnosticsFailure) {
            log.debug(
                    "Agent tool trace diagnostics fallback: {}",
                    diagnosticsFailure.getClass().getName());
            diagnostics = AgentToolDiagnostics.fallback(operation, "");
        }
        return new AgentToolResult(
                "",
                status,
                errorCode,
                diagnostics.withErrorCode(errorCode));
    }

    private long remainingBudgetMs(AgentTurnBudget turnBudget) {
        if (turnBudget == null) {
            return 0;
        }
        long totalNanos = turnBudget.totalBudget().toNanos();
        long elapsedNanos = turnBudget.elapsed().toNanos();
        return Math.max(0, totalNanos - elapsedNanos) / 1_000_000L;
    }

    private long elapsedMillis(long startedAtMs) {
        return Math.max(0, System.currentTimeMillis() - startedAtMs);
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
            Runnable traceTerminator,
            EvidenceState evidenceState) {
        traceTerminator.run();
        trace.setErrorMessage(message);
        emitError(sink, message);
        return AgentLoopResult.terminal(
                status,
                transcript,
                message,
                evidenceState.evidenceSet(),
                evidenceState.evidenceRequired());
    }

    private AgentLoopResult stopIfTurnUnavailable(
            AgentLoopRequest request,
            AgentExecutionTrace trace,
            Consumer<AgentEvent> sink,
            List<String> transcript,
            EvidenceState evidenceState) {
        if (request.turnBudget() == null) {
            return null;
        }
        try {
            request.turnBudget().check("loop");
            return null;
        } catch (AgentTurnBudget.UnavailableException exception) {
            return terminateUnavailable(exception, transcript, trace, sink, evidenceState);
        }
    }

    private AgentLoopResult terminateUnavailable(
            AgentTurnBudget.UnavailableException exception,
            List<String> transcript,
            AgentExecutionTrace trace,
            Consumer<AgentEvent> sink,
            EvidenceState evidenceState) {
        boolean cancelled = exception.reason() == AgentTurnBudget.UnavailableReason.CANCELLED;
        if (!cancelled) {
            trace.recordTimeoutStage(exception.stage());
        }
        trace.recordCancellation(cancelled);
        return terminate(
                cancelled ? AgentLoopResult.Status.CANCELLED : AgentLoopResult.Status.TURN_TIMEOUT,
                transcript,
                cancelled
                        ? agentDomainConfig.errors().modelCallInterrupted()
                        : agentDomainConfig.errors().responseTimeout(),
                trace,
                sink,
                cancelled ? trace::terminateCancelled : trace::terminateTimeout,
                evidenceState);
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

    private String serializeToolInput(Map<String, Object> input) {
        try {
            return objectMapper.writeValueAsString(input == null ? Map.of() : input);
        } catch (Exception exception) {
            log.debug("Agent tool trace input serialization fallback: {}",
                    exception.getClass().getName());
            return String.valueOf(input);
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

    private WebSearchDiscovery parseWebSearchDiscovery(String observation) {
        try {
            JsonNode root = objectMapper.readTree(observation == null ? "" : observation);
            if (root == null || !"web_search_results".equals(root.path("kind").asText())) {
                return WebSearchDiscovery.invalid();
            }
            LinkedHashSet<String> urls = new LinkedHashSet<>();
            JsonNode results = root.path("results");
            if (results.isArray()) {
                for (JsonNode result : results) {
                    String normalized = normalizeResearchUrl(result.path("url").asText(""));
                    if (!normalized.isBlank()) {
                        urls.add(normalized);
                    }
                }
            }
            return new WebSearchDiscovery(true, Set.copyOf(urls));
        } catch (Exception exception) {
            log.debug("Ignoring malformed web_search observation envelope", exception);
            return WebSearchDiscovery.invalid();
        }
    }

    private String parseWebFetchRequestedUrl(String observation) {
        try {
            JsonNode root = objectMapper.readTree(observation == null ? "" : observation);
            if (root == null || !"web_fetched_page".equals(root.path("kind").asText())) {
                return "";
            }
            return normalizeResearchUrl(root.path("requestedUrl").asText(""));
        } catch (Exception exception) {
            log.debug("Ignoring malformed web_fetch observation envelope", exception);
            return "";
        }
    }

    private static String normalizeResearchUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl == null ? "" : rawUrl.strip()).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || host == null
                    || host.isBlank()
                    || uri.getUserInfo() != null) {
                return "";
            }
            int port = uri.getPort();
            if (("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443)) {
                port = -1;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            String authorityHost = normalizedHost.contains(":")
                    ? "[" + normalizedHost + "]"
                    : normalizedHost;
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            StringBuilder normalized = new StringBuilder(scheme)
                    .append("://")
                    .append(authorityHost);
            if (port >= 0) {
                normalized.append(':').append(port);
            }
            normalized.append(path);
            if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
                normalized.append('?').append(uri.getRawQuery());
            }
            return normalized.toString();
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private record LlmFailure(String status, String errorCode) {
    }

    private record WebSearchDiscovery(boolean valid, Set<String> candidateUrls) {

        private WebSearchDiscovery {
            candidateUrls = candidateUrls == null ? Set.of() : Set.copyOf(candidateUrls);
        }

        private static WebSearchDiscovery invalid() {
            return new WebSearchDiscovery(false, Set.of());
        }
    }

    private static final class EvidenceState {
        private EvidenceSet evidenceSet;
        private final boolean initialRequired;
        private boolean dynamicAdded;
        private boolean webSearchNeedsFetch;
        private Set<String> pendingWebCandidateUrls = Set.of();
        private final Map<Integer, String> canonicalEvidenceObservations = new LinkedHashMap<>();

        private EvidenceState(EvidenceSet evidenceSet, boolean initialRequired) {
            this.evidenceSet = evidenceSet == null ? EvidenceSet.empty() : evidenceSet;
            this.initialRequired = initialRequired;
        }

        private String merge(List<Evidence> candidates) {
            List<Evidence> previousItems = evidenceSet.items();
            EvidenceSet merged = evidenceSet.append(candidates);
            if (merged == evidenceSet) {
                return "";
            }
            List<Evidence> changedItems = new ArrayList<>();
            List<Evidence> mergedItems = merged.items();
            for (int index = 0; index < mergedItems.size(); index++) {
                if (index >= previousItems.size()
                        || !mergedItems.get(index).equals(previousItems.get(index))) {
                    changedItems.add(mergedItems.get(index));
                }
            }
            evidenceSet = merged;
            dynamicAdded = true;
            return evidenceSet.renderForPromptItems(changedItems);
        }

        private EvidenceSet evidenceSet() {
            return evidenceSet;
        }

        private boolean evidenceRequired() {
            return initialRequired || dynamicAdded;
        }

        private void recordEvidenceObservation(int transcriptIndex, String canonicalObservation) {
            if (transcriptIndex >= 0 && canonicalObservation != null) {
                canonicalEvidenceObservations.put(transcriptIndex, canonicalObservation);
            }
        }

        private List<String> transcriptForFinalAnswer(List<String> transcript) {
            if (transcript == null || transcript.isEmpty() || canonicalEvidenceObservations.isEmpty()) {
                return transcript == null ? List.of() : List.copyOf(transcript);
            }
            List<String> canonical = new ArrayList<>(transcript);
            for (Map.Entry<Integer, String> replacement : canonicalEvidenceObservations.entrySet()) {
                if (replacement.getKey() < canonical.size()) {
                    canonical.set(replacement.getKey(), replacement.getValue());
                }
            }
            return List.copyOf(canonical);
        }

        private void recordSuccessfulWebSearch(WebSearchDiscovery discovery) {
            if (discovery == null || !discovery.valid()) {
                webSearchNeedsFetch = true;
                return;
            }
            if (!discovery.candidateUrls().isEmpty()) {
                LinkedHashSet<String> accumulated = new LinkedHashSet<>(pendingWebCandidateUrls);
                accumulated.addAll(discovery.candidateUrls());
                pendingWebCandidateUrls = Set.copyOf(accumulated);
                webSearchNeedsFetch = true;
            }
        }

        private void recordSuccessfulWebFetch(String requestedUrl, List<Evidence> evidence) {
            if (webSearchNeedsFetch
                    && requestedUrl != null
                    && !requestedUrl.isBlank()
                    && pendingWebCandidateUrls.contains(requestedUrl)
                    && evidence != null
                    && !evidence.isEmpty()) {
                webSearchNeedsFetch = false;
                pendingWebCandidateUrls = Set.of();
            }
        }

        private boolean webFetchPending() {
            return webSearchNeedsFetch;
        }
    }
}
