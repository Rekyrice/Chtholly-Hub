package com.chtholly.agent.response;

import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.runtime.AgentBoundedCallExecutor;
import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.runtime.AgentSpanAttributes;
import com.chtholly.agent.runtime.AgentTurnBudget;
import io.micrometer.observation.Observation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Performs the single allowed action-envelope retry and citation-only repair calls.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentFinalAnswerRepairService {

    private final AgentLlmInvoker llmInvoker;
    private final AgentProperties properties;
    private final AgentObservationService observationService;
    private final AgentBoundedCallExecutor boundedCallExecutor;
    private final AgentFinalAnswerProtocol protocol;

    /** Creates the final-answer repair boundary. */
    public AgentFinalAnswerRepairService(
            AgentLlmInvoker llmInvoker,
            AgentProperties properties,
            AgentObservationService observationService,
            AgentBoundedCallExecutor boundedCallExecutor,
            AgentFinalAnswerProtocol protocol) {
        this.llmInvoker = llmInvoker;
        this.properties = properties;
        this.observationService = observationService;
        this.boundedCallExecutor = boundedCallExecutor;
        this.protocol = protocol;
    }

    /**
     * Retries one invalid internal action envelope as client-visible Markdown.
     *
     * @return repaired answer
     * @throws AgentInvalidFinalAnswerException when the one repair attempt remains invalid
     */
    public String repairActionEnvelope(
            String system,
            String userPrompt,
            AgentExecutionTrace trace,
            Observation agentSpan,
            int stepIndex,
            AgentTurnBudget turnBudget) {
        String retryPrompt = userPrompt + """


                上一次输出误用了内部工具协议。请重新回答当前问题：
                只输出给用户阅读的自然语言 Markdown，不得输出 JSON、action 字段或工具调用协议。
                """;
        long startedAt = System.currentTimeMillis();
        long budgetBeforeMs = AgentResponseSupport.remainingBudgetMs(turnBudget);
        int inputChars = system.length() + retryPrompt.length();
        Observation repairSpan = observationService.startLlmSpan(agentSpan, properties.getModel());
        boolean callRecorded = false;
        boolean spanClosed = false;
        try {
            String rawRepaired = boundedCallExecutor.execute(
                    () -> llmInvoker.call(system, retryPrompt, 0.2, 1024),
                    turnBudget,
                    "final_answer_repair");
            String repaired = protocol.truncate(rawRepaired);
            boolean actionEnvelope = protocol.isActionEnvelope(repaired);
            trace.recordLlmCall(
                    stepIndex,
                    "FINAL_ANSWER_REPAIR",
                    properties.getModel(),
                    actionEnvelope ? "INVALID_OUTPUT" : "SUCCESS",
                    actionEnvelope ? "FINAL_ACTION_ENVELOPE" : "",
                    2,
                    budgetBeforeMs,
                    AgentResponseSupport.remainingBudgetMs(turnBudget),
                    System.currentTimeMillis() - startedAt,
                    inputChars,
                    rawRepaired == null ? 0 : rawRepaired.length(),
                    null,
                    AgentExecutionTrace.LlmExchange.success(
                            system, retryPrompt, rawRepaired));
            callRecorded = true;
            if (actionEnvelope) {
                observationService.finishSpanError(
                        repairSpan,
                        "final_action_envelope",
                        AgentSpanAttributes.llm("invalid_output"),
                        Map.of("error.type", "FINAL_ACTION_ENVELOPE"));
                spanClosed = true;
                throw new AgentInvalidFinalAnswerException("FINAL_ACTION_ENVELOPE");
            }
            observationService.finishSpan(
                    repairSpan, AgentSpanAttributes.llm("ok"), Map.of());
            spanClosed = true;
            return repaired;
        } catch (AgentTurnBudget.UnavailableException unavailable) {
            if (!callRecorded) {
                recordActionRepairFailure(
                        trace,
                        stepIndex,
                        system,
                        retryPrompt,
                        startedAt,
                        budgetBeforeMs,
                        inputChars,
                        turnBudget,
                        unavailable.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                                ? "CANCELLED"
                                : "TIMEOUT",
                        unavailable.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                                ? "TURN_CANCELLED"
                                : "TURN_TIMEOUT",
                        unavailable);
            }
            if (!spanClosed) {
                observationService.finishSpanError(
                        repairSpan,
                        unavailable.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                                ? "final_repair_cancelled"
                                : "final_repair_timeout",
                        AgentSpanAttributes.llm("aborted"),
                        Map.of());
            }
            throw unavailable;
        } catch (AgentInvalidFinalAnswerException invalidAnswer) {
            throw invalidAnswer;
        } catch (Exception exception) {
            if (!callRecorded) {
                boolean timeout = AgentResponseSupport.isTimeout(exception);
                recordActionRepairFailure(
                        trace,
                        stepIndex,
                        system,
                        retryPrompt,
                        startedAt,
                        budgetBeforeMs,
                        inputChars,
                        turnBudget,
                        timeout ? "TIMEOUT" : "ERROR",
                        timeout ? "LLM_TIMEOUT" : "LLM_ERROR",
                        exception);
            }
            if (!spanClosed) {
                observationService.finishSpanError(
                        repairSpan,
                        AgentResponseSupport.isTimeout(exception)
                                ? "final_repair_timeout"
                                : "final_repair_error",
                        AgentSpanAttributes.llm("error"),
                        Map.of());
            }
            throw new AgentInvalidFinalAnswerException(
                    "FINAL_ANSWER_REPAIR_FAILED", exception);
        }
    }

    /**
     * Attempts one citation-only repair and rejects any change to answer wording.
     *
     * @return repaired answer, or the original candidate when repair is unsafe
     */
    public String repairMissingCitations(
            String candidate,
            EvidenceSet evidenceSet,
            AgentExecutionTrace trace,
            Observation agentSpan,
            int stepIndex,
            AgentTurnBudget turnBudget) {
        String allowedIds = evidenceSet.items().stream()
                .map(com.chtholly.agent.evidence.Evidence::citationId)
                .collect(Collectors.joining(", "));
        String system = """
                你只负责修复引用格式。保持原答案的全部文字、顺序和事实不变，
                只在确有对应证据的句子末尾添加允许的 [E#]。
                不得改写、删减、补充事实，也不得使用未列出的编号。
                只输出修复后的完整答案。""";
        String userPrompt = "允许的引用编号：" + allowedIds
                + "\n\n" + evidenceSet.renderForPrompt()
                + "\n\n待修复答案：\n" + candidate;
        long startedAt = System.currentTimeMillis();
        long budgetBeforeMs = AgentResponseSupport.remainingBudgetMs(turnBudget);
        int inputChars = system.length() + userPrompt.length();
        Observation repairSpan = observationService.startLlmSpan(
                agentSpan, properties.getModel());
        boolean spanClosed = false;
        try {
            String rawRepaired = boundedCallExecutor.execute(
                    () -> llmInvoker.call(system, userPrompt, 0.0, 1024),
                    turnBudget,
                    "citation_repair");
            String repaired = protocol.truncate(rawRepaired);
            trace.recordLlmCall(
                    stepIndex,
                    "CITATION_REPAIR",
                    properties.getModel(),
                    "SUCCESS",
                    "",
                    1,
                    budgetBeforeMs,
                    AgentResponseSupport.remainingBudgetMs(turnBudget),
                    System.currentTimeMillis() - startedAt,
                    inputChars,
                    rawRepaired == null ? 0 : rawRepaired.length(),
                    null,
                    AgentExecutionTrace.LlmExchange.success(
                            system, userPrompt, rawRepaired));
            observationService.finishSpan(
                    repairSpan, AgentSpanAttributes.llm("ok"), Map.of());
            spanClosed = true;
            return protocol.sameContentExceptCitations(candidate, repaired)
                    ? repaired
                    : candidate;
        } catch (AgentTurnBudget.UnavailableException unavailable) {
            recordCitationRepairFailure(
                    trace,
                    stepIndex,
                    system,
                    userPrompt,
                    startedAt,
                    budgetBeforeMs,
                    inputChars,
                    turnBudget,
                    unavailable.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                            ? "CANCELLED"
                            : "TIMEOUT",
                    unavailable.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                            ? "TURN_CANCELLED"
                            : "TURN_TIMEOUT",
                    unavailable);
            if (!spanClosed) {
                observationService.finishSpanError(
                        repairSpan,
                        unavailable.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                                ? "citation_repair_cancelled"
                                : "citation_repair_timeout",
                        AgentSpanAttributes.llm("aborted"),
                        Map.of());
            }
            throw unavailable;
        } catch (Exception exception) {
            boolean timeout = AgentResponseSupport.isTimeout(exception);
            recordCitationRepairFailure(
                    trace,
                    stepIndex,
                    system,
                    userPrompt,
                    startedAt,
                    budgetBeforeMs,
                    inputChars,
                    turnBudget,
                    timeout ? "TIMEOUT" : "ERROR",
                    timeout ? "LLM_TIMEOUT" : "LLM_ERROR",
                    exception);
            if (!spanClosed) {
                observationService.finishSpanError(
                        repairSpan,
                        timeout ? "citation_repair_timeout" : "citation_repair_error",
                        AgentSpanAttributes.llm(timeout ? "timeout" : "error"),
                        Map.of());
            }
            log.warn("Agent citation repair failed ({})", exception.getClass().getSimpleName());
            return candidate;
        }
    }

    private void recordActionRepairFailure(
            AgentExecutionTrace trace,
            int stepIndex,
            String system,
            String prompt,
            long startedAt,
            long budgetBeforeMs,
            int inputChars,
            AgentTurnBudget turnBudget,
            String status,
            String code,
            Throwable failure) {
        trace.recordLlmCall(
                stepIndex,
                "FINAL_ANSWER_REPAIR",
                properties.getModel(),
                status,
                code,
                2,
                budgetBeforeMs,
                AgentResponseSupport.remainingBudgetMs(turnBudget),
                System.currentTimeMillis() - startedAt,
                inputChars,
                0,
                null,
                AgentExecutionTrace.LlmExchange.failure(system, prompt, "", failure));
    }

    private void recordCitationRepairFailure(
            AgentExecutionTrace trace,
            int stepIndex,
            String system,
            String prompt,
            long startedAt,
            long budgetBeforeMs,
            int inputChars,
            AgentTurnBudget turnBudget,
            String status,
            String code,
            Throwable failure) {
        trace.recordLlmCall(
                stepIndex,
                "CITATION_REPAIR",
                properties.getModel(),
                status,
                code,
                1,
                budgetBeforeMs,
                AgentResponseSupport.remainingBudgetMs(turnBudget),
                System.currentTimeMillis() - startedAt,
                inputChars,
                0,
                null,
                AgentExecutionTrace.LlmExchange.failure(system, prompt, "", failure));
    }
}
