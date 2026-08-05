package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentAction;
import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.AgentTool;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.Observation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Executes the bounded Think-Act-Observe portion of one agent turn. */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentLoopExecutor {

    private final AgentDecisionGateway decisionGateway;
    private final AgentToolCallService toolCallService;
    private final AgentActionParser actionParser;
    private final AgentLoopCompletionPolicy completionPolicy;
    private final ObjectMapper objectMapper;
    private final AgentDomainConfig agentDomainConfig;

    /** Creates the loop state machine from its phase collaborators. */
    @Autowired
    public AgentLoopExecutor(
            AgentDecisionGateway decisionGateway,
            AgentToolCallService toolCallService,
            AgentActionParser actionParser,
            AgentLoopCompletionPolicy completionPolicy,
            ObjectMapper objectMapper,
            AgentDomainConfig agentDomainConfig) {
        this.decisionGateway = decisionGateway;
        this.toolCallService = toolCallService;
        this.actionParser = actionParser;
        this.completionPolicy = completionPolicy;
        this.objectMapper = objectMapper;
        this.agentDomainConfig = agentDomainConfig;
    }

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
        int initialTranscriptSize = transcript.size();
        AgentEvidenceTracker evidenceState = new AgentEvidenceTracker(
                request.evidenceSet(), request.evidenceRequired(), objectMapper);
        AgentLoopCompletionPolicy.CompletionState completionState =
                completionPolicy.begin(request);

        for (int step = 0; step < request.maxSteps(); step++) {
            AgentLoopResult unavailable = stopIfTurnUnavailable(
                    request, trace, sink, transcript, evidenceState);
            if (unavailable != null) {
                return unavailable;
            }
            String userPrompt = String.join("\n\n", transcript);
            int inputChars = request.systemPrompt().length() + userPrompt.length();
            long stepLlmStart = System.currentTimeMillis();
            String llmOut;
            try {
                llmOut = decisionGateway.decide(
                        request.systemPrompt(), userPrompt, inputChars, step, trace,
                        request.turnBudget(), agentSpan);
            } catch (AgentTurnBudget.UnavailableException exception) {
                return terminateUnavailable(exception, transcript, trace, sink, evidenceState);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (request.turnBudget() != null && request.turnBudget().isCancelled()) {
                    return terminateUnavailable(
                            AgentTurnBudget.unavailableForStage(
                                    AgentTurnBudget.UnavailableReason.CANCELLED,
                                    "loop_llm"),
                            transcript,
                            trace,
                            sink,
                            evidenceState);
                }
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
                log.warn("Agent LLM call timed out (>{}s)", decisionGateway.timeoutSeconds());
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
            AgentAction action;
            try {
                action = actionParser.parse(llmOut);
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
                AgentLoopCompletionPolicy.CompletionGate completionGate =
                        completionPolicy.evaluate(completionState, evidenceState);
                if (!completionGate.ready()) {
                    emitObserve(sink, completionGate.observation());
                    appendExchange(transcript, llmOut, completionGate.observation());
                    trace.recordStep(step, completionGate.traceAction(), stepLlmMs, 0);
                    continue;
                }
                return AgentLoopResult.finalReady(
                        sanitizeFinalTranscript(
                                evidenceState.transcriptForFinalAnswer(transcript),
                                initialTranscriptSize),
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

            emitAct(sink, tool.name(), action.input());
            AgentToolCallService.ToolCallOutcome toolCall;
            try {
                toolCall = toolCallService.execute(
                        tool, action, request, trace, agentSpan, step, evidenceState);
            } catch (AgentTurnBudget.UnavailableException exception) {
                return terminateUnavailable(exception, transcript, trace, sink, evidenceState);
            }
            completionPolicy.recordToolResult(
                    completionState, tool.name(), toolCall.result().status());
            emitObserve(sink, toolCall.observation());
            trace.recordStep(step, tool.name(), stepLlmMs, toolCall.durationMs());
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
            if (toolCall.result().status() == AgentToolResult.Status.INTERRUPTED
                    && Thread.currentThread().isInterrupted()) {
                return terminate(
                        AgentLoopResult.Status.TOOL_INTERRUPTED,
                        transcript,
                        agentDomainConfig.errors().toolInterrupted(),
                        trace,
                        sink,
                        trace::terminateError,
                        evidenceState);
            }
            appendExchange(transcript, llmOut, toolCall.observation());
            if (toolCall.containsDynamicEvidence()) {
                evidenceState.recordEvidenceObservation(
                        transcript.size() - 1,
                        agentDomainConfig.context().observationLabel()
                                + " " + toolCall.canonicalObservation());
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
            AgentEvidenceTracker evidenceState) {
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
            AgentEvidenceTracker evidenceState) {
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
            AgentEvidenceTracker evidenceState) {
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

    private List<String> sanitizeFinalTranscript(List<String> transcript, int preservedEntries) {
        if (transcript == null || transcript.isEmpty()) {
            return List.of();
        }
        int preserved = Math.min(Math.max(0, preservedEntries), transcript.size());
        String assistantPrefix = agentDomainConfig.context().assistantLabel().strip();
        String parseErrorEntry = (agentDomainConfig.context().observationLabel()
                + " " + agentDomainConfig.systemPrompt().parseErrorObservation()).strip();
        List<String> sanitized = new ArrayList<>(transcript.size());
        for (int index = 0; index < transcript.size(); index++) {
            String entry = transcript.get(index);
            if (index >= preserved) {
                String normalized = entry == null ? "" : entry.strip();
                if (normalized.startsWith(assistantPrefix)
                        || normalized.equals(parseErrorEntry)) {
                    continue;
                }
            }
            sanitized.add(entry);
        }
        return List.copyOf(sanitized);
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

    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength) + "...";
    }

}
