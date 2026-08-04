package com.chtholly.agent.response;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.CharacterSoulService;
import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.runtime.AgentSpanAttributes;
import com.chtholly.agent.runtime.AgentTurnBudget;
import com.chtholly.agent.runtime.AgentTurnCompletion;
import io.micrometer.observation.Observation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Generates short persona-consistent responses for clarification and evidence safety boundaries.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentBoundaryResponseService {

    private final AgentLlmInvoker llmInvoker;
    private final AgentProperties properties;
    private final CharacterSoulService characterSoulService;
    private final AgentObservationService observationService;
    private final AgentTurnCompletion completion;

    /** Creates the boundary-response generator. */
    public AgentBoundaryResponseService(
            AgentLlmInvoker llmInvoker,
            AgentProperties properties,
            CharacterSoulService characterSoulService,
            AgentObservationService observationService,
            AgentTurnCompletion completion) {
        this.llmInvoker = llmInvoker;
        this.properties = properties;
        this.characterSoulService = characterSoulService;
        this.observationService = observationService;
        this.completion = completion;
    }

    /**
     * Completes a turn with a safe boundary explanation instead of entering or continuing the loop.
     */
    public void complete(
            AgentExecutionTrace.OutcomeReason reason,
            String skillId,
            String detail,
            String question,
            AgentConversationMemory memory,
            Consumer<AgentEvent> sink,
            AgentExecutionTrace trace,
            Observation agentSpan,
            AgentTurnBudget turnBudget) {
        turnBudget.check("boundary_response");
        String fallback = fallback(reason, skillId);
        String system = characterSoulService.getSoulContent() + "\n\n"
                + "## 当前响应边界\n\n"
                + "reason=" + reason.name() + "\n"
                + "skillId=" + (skillId == null ? "" : skillId) + "\n"
                + "detail=" + (detail == null ? "" : detail) + "\n\n"
                + "只输出一段简短自然语言。遵循角色设定，但不要堆叠语气词或卖萌。"
                + "不得回答原任务、编造站内事实或生成引用；只说明当前边界并给出下一步。";
        String userPrompt = "请根据稳定原因码生成对用户可见的边界提示。";
        String answer = fallback;
        Observation llmSpan = observationService.startLlmSpan(agentSpan, properties.getModel());
        long startedAt = System.currentTimeMillis();
        long budgetBeforeMs = AgentResponseSupport.remainingBudgetMs(turnBudget);
        AtomicLong firstTokenCallMs = new AtomicLong(-1);
        AtomicLong firstTokenTurnMs = new AtomicLong(-1);
        AtomicLong modelOutputChars = new AtomicLong();
        StringBuilder generated = new StringBuilder();
        try {
            llmInvoker.stream(
                            system,
                            userPrompt,
                            0.2,
                            192,
                            turnBudget.remaining(
                                    "boundary_response",
                                    Duration.ofSeconds(Math.max(
                                            1, properties.getLlmTimeoutSeconds()))))
                    .doOnNext(chunk -> recordChunk(
                            chunk,
                            generated,
                            modelOutputChars,
                            firstTokenCallMs,
                            firstTokenTurnMs,
                            startedAt,
                            trace.getStartedAtMs()))
                    .blockLast();
            String candidate = truncate(generated.toString());
            if (isSafe(reason, candidate)) {
                answer = candidate;
            }
            recordSuccess(
                    trace,
                    reason,
                    system,
                    userPrompt,
                    generated,
                    startedAt,
                    budgetBeforeMs,
                    modelOutputChars,
                    firstTokenCallMs,
                    turnBudget);
            observationService.finishSpan(
                    llmSpan,
                    AgentSpanAttributes.llm("ok"),
                    Map.of("response.boundary_reason", reason.name()));
        } catch (AgentTurnBudget.UnavailableException unavailable) {
            recordAborted(
                    trace,
                    system,
                    userPrompt,
                    generated,
                    startedAt,
                    budgetBeforeMs,
                    modelOutputChars,
                    firstTokenCallMs,
                    turnBudget,
                    unavailable.reason(),
                    unavailable);
            observationService.finishSpanError(
                    llmSpan,
                    unavailable.reason() == AgentTurnBudget.UnavailableReason.CANCELLED
                            ? "boundary_cancelled"
                            : "boundary_turn_timeout",
                    AgentSpanAttributes.llm("aborted"),
                    Map.of("response.boundary_reason", reason.name()));
            throw unavailable;
        } catch (Exception exception) {
            if (turnBudget.isCancelled() || turnBudget.isExpired()) {
                AgentTurnBudget.UnavailableReason unavailableReason = turnBudget.isCancelled()
                        ? AgentTurnBudget.UnavailableReason.CANCELLED
                        : AgentTurnBudget.UnavailableReason.TIMEOUT;
                AgentTurnBudget.UnavailableException unavailable =
                        AgentTurnBudget.unavailableForStage(
                                unavailableReason, "boundary_response");
                recordAborted(
                        trace,
                        system,
                        userPrompt,
                        generated,
                        startedAt,
                        budgetBeforeMs,
                        modelOutputChars,
                        firstTokenCallMs,
                        turnBudget,
                        unavailableReason,
                        exception);
                observationService.finishSpanError(
                        llmSpan,
                        unavailableReason == AgentTurnBudget.UnavailableReason.CANCELLED
                                ? "boundary_cancelled"
                                : "boundary_turn_timeout",
                        AgentSpanAttributes.llm("aborted"),
                        Map.of("response.boundary_reason", reason.name()));
                throw unavailable;
            }
            boolean timeout = AgentResponseSupport.isTimeout(exception);
            trace.recordLlmCall(
                    0,
                    "BOUNDARY_RESPONSE",
                    properties.getModel(),
                    timeout ? "TIMEOUT" : "ERROR",
                    timeout ? "LLM_TIMEOUT" : "LLM_ERROR",
                    1,
                    budgetBeforeMs,
                    AgentResponseSupport.remainingBudgetMs(turnBudget),
                    System.currentTimeMillis() - startedAt,
                    system.length() + userPrompt.length(),
                    AgentResponseSupport.saturatedCharCount(modelOutputChars.get()),
                    firstTokenCallMs.get() >= 0 ? firstTokenCallMs.get() : null,
                    AgentExecutionTrace.LlmExchange.failure(
                            system, userPrompt, generated.toString(), exception));
            if (trace.getFailureType() == AgentExecutionTrace.FailureType.NONE) {
                trace.markFailure(timeout
                        ? AgentExecutionTrace.FailureType.LLM_TIMEOUT
                        : AgentExecutionTrace.FailureType.INTERNAL_ERROR);
            }
            observationService.finishSpanError(
                    llmSpan,
                    timeout ? "boundary_timeout" : "boundary_error",
                    AgentSpanAttributes.llm(timeout ? "timeout" : "error"),
                    Map.of("response.boundary_reason", reason.name()));
            log.warn(
                    "Agent boundary response fell back to safe copy: reason={}, error={}",
                    reason,
                    exception.getClass().getSimpleName());
        }
        long readyMs = System.currentTimeMillis() - trace.getStartedAtMs();
        completion.completeVisibleAnswer(
                memory,
                question,
                answer,
                turnBudget,
                trace.getTurnControl(),
                trace,
                sink,
                firstTokenTurnMs.get() >= 0 ? firstTokenTurnMs.get() : null,
                readyMs);
    }

    private void recordChunk(
            String chunk,
            StringBuilder generated,
            AtomicLong modelOutputChars,
            AtomicLong firstTokenCallMs,
            AtomicLong firstTokenTurnMs,
            long startedAt,
            long turnStartedAt) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        firstTokenCallMs.compareAndSet(-1, now - startedAt);
        firstTokenTurnMs.compareAndSet(-1, now - turnStartedAt);
        modelOutputChars.addAndGet(chunk.length());
        generated.append(chunk);
    }

    private void recordSuccess(
            AgentExecutionTrace trace,
            AgentExecutionTrace.OutcomeReason reason,
            String system,
            String userPrompt,
            StringBuilder generated,
            long startedAt,
            long budgetBeforeMs,
            AtomicLong modelOutputChars,
            AtomicLong firstTokenCallMs,
            AgentTurnBudget turnBudget) {
        trace.recordLlmCall(
                0,
                "BOUNDARY_RESPONSE",
                properties.getModel(),
                "SUCCESS",
                "",
                1,
                budgetBeforeMs,
                AgentResponseSupport.remainingBudgetMs(turnBudget),
                System.currentTimeMillis() - startedAt,
                system.length() + userPrompt.length(),
                AgentResponseSupport.saturatedCharCount(modelOutputChars.get()),
                firstTokenCallMs.get() >= 0 ? firstTokenCallMs.get() : null,
                AgentExecutionTrace.LlmExchange.success(
                        system, userPrompt, generated.toString()));
    }

    private void recordAborted(
            AgentExecutionTrace trace,
            String system,
            String userPrompt,
            StringBuilder generated,
            long startedAt,
            long budgetBeforeMs,
            AtomicLong modelOutputChars,
            AtomicLong firstTokenCallMs,
            AgentTurnBudget turnBudget,
            AgentTurnBudget.UnavailableReason unavailableReason,
            Throwable failure) {
        boolean cancelled = unavailableReason == AgentTurnBudget.UnavailableReason.CANCELLED;
        trace.recordLlmCall(
                0,
                "BOUNDARY_RESPONSE",
                properties.getModel(),
                cancelled ? "CANCELLED" : "TIMEOUT",
                cancelled ? "TURN_CANCELLED" : "TURN_TIMEOUT",
                1,
                budgetBeforeMs,
                AgentResponseSupport.remainingBudgetMs(turnBudget),
                System.currentTimeMillis() - startedAt,
                system.length() + userPrompt.length(),
                AgentResponseSupport.saturatedCharCount(modelOutputChars.get()),
                firstTokenCallMs.get() >= 0 ? firstTokenCallMs.get() : null,
                AgentExecutionTrace.LlmExchange.failure(
                        system, userPrompt, generated.toString(), failure));
    }

    private String truncate(String answer) {
        String normalized = answer == null ? "" : answer.strip();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 400);
    }

    private boolean isSafe(
            AgentExecutionTrace.OutcomeReason reason,
            String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.matches("(?s).*\\[E\\d+].*")) {
            return false;
        }
        return switch (reason) {
            case NEEDS_CLARIFICATION -> containsAny(
                    candidate, "告诉", "提供", "贴", "哪", "什么", "？", "?");
            case NO_EVIDENCE -> containsAny(candidate, "没有", "不足", "暂时", "找不到")
                    && containsAny(candidate, "资料", "证据", "依据");
            case INVALID_CITATION -> containsAny(
                    candidate, "不能", "无法", "对不上", "不可靠", "不一致")
                    && containsAny(candidate, "引用", "资料", "证据", "依据");
            case NONE, MODEL_FAILURE -> true;
        };
    }

    private boolean containsAny(String input, String... terms) {
        for (String term : terms) {
            if (input.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String fallback(
            AgentExecutionTrace.OutcomeReason reason,
            String skillId) {
        return switch (reason) {
            case NEEDS_CLARIFICATION -> switch (skillId == null ? "" : skillId) {
                case "evidence-outline" ->
                        "嗯，可以呀。不过你还没有告诉我想写什么主题。给我一个主题，或者指定一篇文章，我再替你把资料和结构整理好。";
                case "page-explain" ->
                        "想让我解释哪一页或哪个概念呢？给我一个对象，我会陪你把它慢慢讲清楚。";
                case "draft-fact-check" ->
                        "把需要核查的草稿或陈述贴给我吧。我会逐条看清楚，再告诉你哪些地方有依据、哪些还不确定。";
                default -> "先告诉我你想使用哪一种任务，或者直接说说想完成什么吧。";
            };
            case NO_EVIDENCE ->
                    "我认真找过了，但站内暂时没有足够资料支撑这次回答。要不要换个主题，或者把你手头的材料交给我整理？";
            case INVALID_CITATION ->
                    "这次的引用和本轮资料对不上，我不能把它当成可靠答案。换一种说法，或者让我重新查一次吧。";
            case NONE, MODEL_FAILURE -> "这次没能整理出可靠的回答。稍后再试一次吧。";
        };
    }
}
