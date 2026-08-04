package com.chtholly.agent.response;

import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.context.AgentContextSnapshot;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.runtime.AgentSpanAttributes;
import com.chtholly.agent.runtime.AgentTurnBudget;
import io.micrometer.observation.Observation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buffers one final-answer model stream and records its complete trace exchange.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentFinalCandidateGenerator {
    private final AgentLlmInvoker llmInvoker;
    private final AgentProperties properties;
    private final AgentObservationService observationService;
    private final AgentFinalAnswerPromptFactory promptFactory;
    private final AgentFinalAnswerProtocol protocol;
    /**
     * Creates the candidate-generation boundary.
     *
     * @param llmInvoker model invocation adapter
     * @param properties agent model properties
     * @param observationService observation adapter
     * @param promptFactory final-answer prompt factory
     * @param protocol final-answer wire protocol validator
     */
    public AgentFinalCandidateGenerator(
            AgentLlmInvoker llmInvoker,
            AgentProperties properties,
            AgentObservationService observationService,
            AgentFinalAnswerPromptFactory promptFactory,
            AgentFinalAnswerProtocol protocol) {
        this.llmInvoker = llmInvoker;
        this.properties = properties;
        this.observationService = observationService;
        this.promptFactory = promptFactory;
        this.protocol = protocol;
    }
    /**
     * Generates one buffered answer candidate without exposing unvalidated chunks.
     *
     * @param request immutable final generation inputs
     * @return successful candidate or classified model failure
     */
    public Result generate(Request request) {
        AgentFinalAnswerPromptFactory.Prompt prompt = promptFactory.build(
                request.contextSnapshot(), request.finalEvidenceSet(), request.transcript());
        String system = prompt.system();
        String userPrompt = prompt.userPrompt();
        int inputChars = system.length() + userPrompt.length();
        int timeoutSec = Math.max(1, properties.getLlmTimeoutSeconds());
        Observation llmSpan = observationService.startLlmSpan(
                request.agentSpan(), properties.getModel());
        long startedAt = System.currentTimeMillis();
        long budgetBeforeMs = AgentResponseSupport.remainingBudgetMs(request.turnBudget());
        AtomicLong firstTokenCallMs = new AtomicLong(-1);
        AtomicLong firstTokenTurnMs = new AtomicLong(-1);
        AtomicLong outputChars = new AtomicLong();
        StringBuilder full = new StringBuilder();
        boolean callRecorded = false;
        boolean spanClosed = false;
        try {
            request.turnBudget().check("final_answer");
            Flux<String> flux = llmInvoker.stream(
                    system,
                    userPrompt,
                    0.3,
                    1024,
                    request.turnBudget().remaining(
                            "final_answer", Duration.ofSeconds(timeoutSec)));
            flux.doOnNext(chunk -> recordChunk(
                    chunk,
                    full,
                    outputChars,
                    firstTokenCallMs,
                    firstTokenTurnMs,
                    startedAt,
                    request.trace().getStartedAtMs())).blockLast();

            String candidate = protocol.truncate(full.toString());
            long durationMs = System.currentTimeMillis() - startedAt;
            boolean actionEnvelope = protocol.isActionEnvelope(candidate);
            request.trace().recordLlmCall(
                    request.stepIndex(),
                    "FINAL_ANSWER",
                    properties.getModel(),
                    actionEnvelope ? "INVALID_OUTPUT" : "SUCCESS",
                    actionEnvelope ? "FINAL_ACTION_ENVELOPE" : "",
                    1,
                    budgetBeforeMs,
                    AgentResponseSupport.remainingBudgetMs(request.turnBudget()),
                    durationMs,
                    inputChars,
                    AgentResponseSupport.saturatedCharCount(outputChars.get()),
                    firstTokenCallMs.get() >= 0 ? firstTokenCallMs.get() : null,
                    AgentExecutionTrace.LlmExchange.success(
                            system, userPrompt, full.toString()));
            callRecorded = true;
            closeSpan(llmSpan, actionEnvelope);
            spanClosed = true;
            return new Result(
                    Status.SUCCESS,
                    candidate,
                    system,
                    userPrompt,
                    durationMs,
                    actionEnvelope ? null
                            : firstTokenTurnMs.get() >= 0 ? firstTokenTurnMs.get() : null,
                    actionEnvelope,
                    "",
                    null);
        } catch (AgentTurnBudget.UnavailableException unavailable) {
            long durationMs = System.currentTimeMillis() - startedAt;
            if (!callRecorded) {
                recordFailure(
                        request,
                        system,
                        userPrompt,
                        full,
                        durationMs,
                        inputChars,
                        budgetBeforeMs,
                        outputChars,
                        firstTokenCallMs,
                        unavailable.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                                ? "CANCELLED" : "TIMEOUT",
                        unavailable.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                                ? "TURN_CANCELLED" : "TURN_TIMEOUT",
                        unavailable);
            }
            if (!spanClosed) {
                observationService.finishSpanError(
                        llmSpan,
                        unavailable.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                                ? "stream_cancelled" : "stream_turn_timeout",
                        AgentSpanAttributes.llm("aborted"),
                        Map.of());
            }
            throw unavailable;
        } catch (Exception failure) {
            long durationMs = System.currentTimeMillis() - startedAt;
            if (request.turnBudget().isCancelled() || request.turnBudget().isExpired()) {
                boolean cancelled = request.turnBudget().isCancelled();
                if (!callRecorded) {
                    recordFailure(
                            request, system, userPrompt, full, durationMs, inputChars,
                            budgetBeforeMs, outputChars, firstTokenCallMs,
                            cancelled ? "CANCELLED" : "TIMEOUT",
                            cancelled ? "TURN_CANCELLED" : "TURN_TIMEOUT",
                            failure);
                }
                if (!spanClosed) {
                    observationService.finishSpanError(
                            llmSpan,
                            cancelled ? "stream_cancelled" : "stream_turn_timeout",
                            AgentSpanAttributes.llm("aborted"),
                            Map.of());
                }
                throw AgentTurnBudget.unavailableForStage(
                        cancelled
                                ? AgentTurnBudget.UnavailableReason.CANCELLED
                                : AgentTurnBudget.UnavailableReason.TIMEOUT,
                        "final_answer");
            }
            boolean timeout = AgentResponseSupport.isTimeout(failure);
            if (!callRecorded) {
                recordFailure(
                        request, system, userPrompt, full, durationMs, inputChars,
                        budgetBeforeMs, outputChars, firstTokenCallMs,
                        timeout ? "TIMEOUT" : "ERROR",
                        timeout ? "LLM_TIMEOUT" : "LLM_ERROR",
                        failure);
            }
            if (!spanClosed) {
                observationService.finishSpanError(
                        llmSpan,
                        timeout ? "stream_timeout" : "stream_error",
                        AgentSpanAttributes.llm(timeout ? "timeout" : "error"),
                        Map.of());
            }
            log.warn("Agent streaming answer {} ({})",
                    timeout ? "timed out" : "failed",
                    failure.getClass().getSimpleName());
            return new Result(
                    timeout ? Status.TIMEOUT : Status.ERROR,
                    "",
                    "",
                    "",
                    durationMs,
                    null,
                    false,
                    timeout ? promptFactory.responseTimeout() : promptFactory.responseFailed(),
                    failure);
        }
    }
    private void closeSpan(Observation llmSpan, boolean actionEnvelope) {
        if (actionEnvelope) {
            observationService.finishSpanError(
                    llmSpan,
                    "final_action_envelope",
                    AgentSpanAttributes.llm("invalid_output"),
                    Map.of("error.type", "FINAL_ACTION_ENVELOPE"));
        } else {
            observationService.finishSpan(
                    llmSpan, AgentSpanAttributes.llm("ok"), Map.of());
        }
    }
    private void recordChunk(
            String chunk,
            StringBuilder full,
            AtomicLong outputChars,
            AtomicLong firstTokenCallMs,
            AtomicLong firstTokenTurnMs,
            long callStartedAt,
            long turnStartedAt) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        firstTokenCallMs.compareAndSet(-1, now - callStartedAt);
        firstTokenTurnMs.compareAndSet(-1, now - turnStartedAt);
        outputChars.addAndGet(chunk.length());
        full.append(chunk);
    }
    private void recordFailure(
            Request request,
            String system,
            String userPrompt,
            StringBuilder full,
            long durationMs,
            int inputChars,
            long budgetBeforeMs,
            AtomicLong outputChars,
            AtomicLong firstTokenCallMs,
            String status,
            String code,
            Throwable failure) {
        request.trace().recordLlmCall(
                request.stepIndex(),
                "FINAL_ANSWER",
                properties.getModel(),
                status,
                code,
                1,
                budgetBeforeMs,
                AgentResponseSupport.remainingBudgetMs(request.turnBudget()),
                durationMs,
                inputChars,
                AgentResponseSupport.saturatedCharCount(outputChars.get()),
                firstTokenCallMs.get() >= 0 ? firstTokenCallMs.get() : null,
                AgentExecutionTrace.LlmExchange.failure(
                        system, userPrompt, full.toString(), failure));
    }
    /** Candidate-generation terminal status. */
    public enum Status {
        SUCCESS,
        TIMEOUT,
        ERROR
    }
    /** Immutable model-generation inputs. */
    public record Request(
            AgentContextSnapshot contextSnapshot,
            EvidenceSet finalEvidenceSet,
            List<String> transcript,
            AgentExecutionTrace trace,
            Observation agentSpan,
            int stepIndex,
            AgentTurnBudget turnBudget) {
    }

    /** Immutable buffered candidate or classified generation failure. */
    public record Result(
            Status status,
            String candidate,
            String system,
            String userPrompt,
            long modelDurationMs,
            Long modelFirstTokenTurnMs,
            boolean actionEnvelope,
            String clientMessage,
            Throwable failure) {
    }
}
