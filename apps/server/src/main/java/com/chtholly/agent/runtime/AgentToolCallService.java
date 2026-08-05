package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentAction;
import com.chtholly.agent.AgentTool;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.observability.AgentToolDiagnostics;
import io.micrometer.observation.Observation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Executes and traces one tool call, including diagnostics and dynamic evidence integration. */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentToolCallService {

    private final AgentToolExecutor toolExecutor;
    private final AgentActionParser actionParser;
    private final AgentObservationService observationService;
    private final AgentDomainConfig domainConfig;

    /**
     * Creates the traced tool-call service.
     *
     * @param toolExecutor bounded tool executor
     * @param actionParser tool input protocol adapter
     * @param observationService observation adapter
     * @param domainConfig stable tool guidance
     */
    public AgentToolCallService(
            AgentToolExecutor toolExecutor,
            AgentActionParser actionParser,
            AgentObservationService observationService,
            AgentDomainConfig domainConfig) {
        this.toolExecutor = toolExecutor;
        this.actionParser = actionParser;
        this.observationService = observationService;
        this.domainConfig = domainConfig;
    }

    /**
     * Executes one action and returns the canonical and model-visible observations.
     *
     * @param tool selected tool
     * @param action parsed action
     * @param request immutable loop request
     * @param trace execution trace
     * @param agentSpan root observation
     * @param step zero-based loop step
     * @param evidenceTracker per-turn evidence state
     * @return completed tool-call outcome
     * @throws AgentTurnBudget.UnavailableException when the whole turn expires or is cancelled
     */
    public ToolCallOutcome execute(
            AgentTool tool,
            AgentAction action,
            AgentLoopRequest request,
            AgentExecutionTrace trace,
            Observation agentSpan,
            int step,
            AgentEvidenceTracker evidenceTracker) {
        Map<String, Object> input = actionParser.prepareToolInput(
                action.input(), request.question(), request.historyBlock());
        String actualToolInput = actionParser.serializeToolInput(input);
        Observation toolSpan = observationService.startToolSpan(agentSpan, tool.name());
        long startedAt = System.currentTimeMillis();
        long budgetBeforeMs = remainingBudgetMs(request.turnBudget());
        AgentToolResult result;
        try {
            result = request.turnBudget() == null
                    ? toolExecutor.execute(tool, input, request.userId())
                    : toolExecutor.execute(
                            tool,
                            input,
                            request.userId(),
                            request.turnBudget().remaining(
                                    "loop_tool", request.turnBudget().totalBudget()));
        } catch (AgentTurnBudget.UnavailableException unavailable) {
            recordUnavailable(
                    tool,
                    input,
                    request.turnBudget(),
                    trace,
                    toolSpan,
                    step,
                    startedAt,
                    budgetBeforeMs,
                    actualToolInput,
                    unavailable);
            throw unavailable;
        } catch (RuntimeException exception) {
            recordExecutorFailure(
                    tool,
                    input,
                    request.turnBudget(),
                    trace,
                    toolSpan,
                    step,
                    startedAt,
                    budgetBeforeMs,
                    actualToolInput);
            throw exception;
        }

        long durationMs = elapsedMillis(startedAt);
        finishToolSpan(toolSpan, result.status());
        String canonicalObservation = augmentObservation(
                tool.name(), result.observation(), result.status());
        String observation = canonicalObservation;
        boolean containsDynamicEvidence = false;
        if (result.status() == AgentToolResult.Status.SUCCESS) {
            String dynamicEvidence = evidenceTracker.merge(result.evidence());
            if (!dynamicEvidence.isBlank()) {
                containsDynamicEvidence = true;
                observation = observation.isBlank()
                        ? dynamicEvidence
                        : observation + "\n\n" + dynamicEvidence;
            }
        }
        evidenceTracker.recordWebToolResult(tool.name(), input, result);
        trace.recordToolCall(
                step,
                tool.name(),
                durationMs,
                budgetBeforeMs,
                remainingBudgetMs(request.turnBudget()),
                normalizedResult(result),
                actualToolInput,
                observation);
        return new ToolCallOutcome(
                result,
                observation,
                canonicalObservation,
                containsDynamicEvidence,
                durationMs);
    }

    private void recordUnavailable(
            AgentTool tool,
            Map<String, Object> input,
            AgentTurnBudget budget,
            AgentExecutionTrace trace,
            Observation toolSpan,
            int step,
            long startedAt,
            long budgetBeforeMs,
            String actualToolInput,
            AgentTurnBudget.UnavailableException unavailable) {
        boolean cancelled = unavailable.reason() == AgentTurnBudget.UnavailableReason.CANCELLED;
        AgentToolResult failure = safeFailureResult(
                tool,
                input,
                cancelled ? AgentToolResult.Status.INTERRUPTED : AgentToolResult.Status.TIMEOUT,
                cancelled ? "TURN_CANCELLED" : "TURN_TIMEOUT");
        trace.recordToolCall(
                step,
                tool.name(),
                elapsedMillis(startedAt),
                budgetBeforeMs,
                remainingBudgetMs(budget),
                failure,
                actualToolInput,
                null);
        observationService.finishSpanError(
                toolSpan,
                cancelled ? "turn_cancelled" : "turn_timeout",
                Map.of("status", unavailable.reason().name().toLowerCase()),
                Map.of());
    }

    private void recordExecutorFailure(
            AgentTool tool,
            Map<String, Object> input,
            AgentTurnBudget budget,
            AgentExecutionTrace trace,
            Observation toolSpan,
            int step,
            long startedAt,
            long budgetBeforeMs,
            String actualToolInput) {
        trace.recordToolCall(
                step,
                tool.name(),
                elapsedMillis(startedAt),
                budgetBeforeMs,
                remainingBudgetMs(budget),
                safeFailureResult(
                        tool, input, AgentToolResult.Status.ERROR, "TOOL_EXECUTOR_ERROR"),
                actualToolInput,
                null);
        observationService.finishSpanError(
                toolSpan,
                "tool_executor_error",
                Map.of("status", "error", "error.type", "INTERNAL_ERROR"),
                Map.of());
    }

    private AgentToolResult normalizedResult(AgentToolResult result) {
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

    private AgentToolResult safeFailureResult(
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
                "", status, errorCode, diagnostics.withErrorCode(errorCode));
    }

    private String augmentObservation(
            String toolName,
            String observation,
            AgentToolResult.Status status) {
        String result = observation == null ? "" : observation;
        boolean emptySiteResult = observation == null
                || domainConfig.systemPrompt().emptySiteResultMarkers()
                        .stream()
                        .anyMatch(result::contains);
        if (emptySiteResult
                && ("fulltext_search".equals(toolName) || "article_rag".equals(toolName))) {
            result = result + "\n\n" + domainConfig.systemPrompt().siteEmptyGuidance();
        }
        if (status == AgentToolResult.Status.TIMEOUT
                && toolName != null
                && toolName.startsWith("bangumi_")) {
            result = result + "\n\n" + domainConfig.systemPrompt().bangumiTimeoutGuidance();
        }
        return result;
    }

    private void finishToolSpan(Observation span, AgentToolResult.Status status) {
        Map<String, String> attributes = AgentSpanAttributes.tool(status);
        switch (status) {
            case TIMEOUT -> observationService.finishSpanError(
                    span, "tool_timeout", attributes, Map.of());
            case ERROR -> observationService.finishSpanError(
                    span, "tool_error", attributes, Map.of());
            case INTERRUPTED -> observationService.finishSpanError(
                    span, "tool_interrupted", attributes, Map.of());
            default -> observationService.finishSpan(span, attributes, Map.of());
        }
    }

    private long remainingBudgetMs(AgentTurnBudget budget) {
        if (budget == null) {
            return 0;
        }
        return Math.max(
                0,
                budget.totalBudget().toNanos() - budget.elapsed().toNanos()) / 1_000_000L;
    }

    private long elapsedMillis(long startedAtMs) {
        return Math.max(0, System.currentTimeMillis() - startedAtMs);
    }

    /** Completed tool-call data consumed by the loop state machine. */
    public record ToolCallOutcome(
            AgentToolResult result,
            String observation,
            String canonicalObservation,
            boolean containsDynamicEvidence,
            long durationMs) {
    }
}
