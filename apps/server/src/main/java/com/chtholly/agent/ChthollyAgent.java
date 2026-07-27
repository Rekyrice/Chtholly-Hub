package com.chtholly.agent;

import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.context.AgentContextSnapshot;
import com.chtholly.agent.context.ContextEngine;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentComponentVersions;
import com.chtholly.agent.observability.AgentMetrics;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.runtime.AgentLoopExecutor;
import com.chtholly.agent.runtime.AgentLoopRequest;
import com.chtholly.agent.runtime.AgentLoopResult;
import com.chtholly.agent.runtime.AgentSpanAttributes;
import com.chtholly.agent.skill.SkillDefinition;
import com.chtholly.agent.skill.SkillExecutionContext;
import com.chtholly.agent.skill.SkillOutputValidator;
import com.chtholly.agent.skill.SkillRequestPlanner;
import com.chtholly.agent.skill.SkillRegistry;
import com.chtholly.agent.skill.SkillSelector;
import com.chtholly.agent.trace.TracePersistenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.Observation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Public orchestration boundary for one agent turn.
 *
 * <p>The bounded reasoning loop is delegated to {@link AgentLoopExecutor}. This service owns
 * context assembly, trace lifetime, final answer streaming, and conversation memory updates.
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
    private final AgentLoopExecutor loopExecutor;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final List<AgentTool> tools;
    private final AgentMetrics agentMetrics;
    private final AgentObservationService agentObservationService;
    private final CharacterSoulService characterSoulService;
    private final ContextEngine contextEngine;
    private final TracePersistenceService tracePersistenceService;
    private final AgentDomainConfig agentDomainConfig;
    private final SkillRegistry skillRegistry;
    private final SkillSelector skillSelector;
    private final SkillRequestPlanner skillRequestPlanner;
    private final SkillOutputValidator skillOutputValidator;

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
        run(question, userId, memory, sessionId, pageContext, null, sink);
    }

    /** Runs one turn with an optional server-validated product task type. */
    public void run(String question, long userId, AgentConversationMemory memory, String sessionId,
                    String pageContext, String taskType, Consumer<AgentEvent> sink) {
        int maxSteps = Math.max(1, properties.getMaxSteps());
        AgentExecutionTrace trace = new AgentExecutionTrace(userId, sessionId, maxSteps);
        trace.recordTurnContext(question, pageContext, properties.getModel(), "candidate");
        Observation agentSpan = agentObservationService.startAgentSpan(trace.getCorrelationId(), userId);
        try (Observation.Scope ignored = agentSpan.openScope()) {
            runInternal(
                    question, userId, memory, sessionId, pageContext, taskType,
                    sink, maxSteps, trace, agentSpan);
        } finally {
            trace.finish();
            agentObservationService.finishSpan(
                    agentSpan, AgentSpanAttributes.agent(trace), Map.of());
            trace.finishAndLog(objectMapper, agentMetrics);
            tracePersistenceService.persist(trace);
        }
    }

    private void runInternal(String question,
                             long userId,
                             AgentConversationMemory memory,
                             String sessionId,
                             String pageContext,
                             String taskType,
                             Consumer<AgentEvent> sink,
                             int maxSteps,
                             AgentExecutionTrace trace,
                             Observation agentSpan) {
        Observation skillSpan = null;
        Observation retrievalSpan = null;
        try {
            if (question == null || question.isBlank()) {
                trace.terminateError();
                trace.markFailure(AgentExecutionTrace.FailureType.INVALID_INPUT);
                trace.setErrorMessage(agentDomainConfig.errors().questionEmpty());
                emitError(sink, agentDomainConfig.errors().questionEmpty());
                return;
            }

            Map<String, AgentTool> toolMap = new LinkedHashMap<>();
            for (AgentTool tool : tools) {
                toolMap.put(tool.name(), tool);
            }

            skillSpan = agentObservationService.startSkillSpan(agentSpan);
            SkillSelector.SkillSelection selection = selectSkill(
                    userId, sessionId, taskType, question, pageContext, toolMap.keySet(), trace);
            if (selection != null
                    && selection.status() == SkillSelector.Status.CLARIFICATION_REQUIRED) {
                trace.markFailure(AgentExecutionTrace.FailureType.SKILL_NO_MATCH);
                trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.NEEDS_CLARIFICATION);
                completeBoundaryResponse(
                        AgentExecutionTrace.OutcomeReason.NEEDS_CLARIFICATION,
                        "",
                        selection.reason(),
                        question,
                        memory,
                        sink,
                        trace,
                        agentSpan);
                return;
            }
            if (isSelected(selection)) {
                toolMap.keySet().retainAll(selection.allowedTools());
            }
            trace.recordTools(toolMap.keySet());

            boolean selected = isSelected(selection);
            SkillDefinition selectedSkill = selected ? selection.definition() : null;
            SkillRequestPlanner.SkillRequestPlan taskPlan = selected
                    ? skillRequestPlanner.plan(selectedSkill, question, pageContext)
                    : null;
            if (taskPlan != null
                    && taskPlan.status() == SkillRequestPlanner.PlanStatus.NEEDS_CLARIFICATION) {
                trace.recordSkillValidation("NEEDS_CLARIFICATION");
                trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.NEEDS_CLARIFICATION);
                completeBoundaryResponse(
                        AgentExecutionTrace.OutcomeReason.NEEDS_CLARIFICATION,
                        selectedSkill.id(),
                        taskPlan.reason(),
                        question,
                        memory,
                        sink,
                        trace,
                        agentSpan);
                return;
            }
            String historyBlock = memory == null ? "" : memory.formatForPrompt();
            retrievalSpan = agentObservationService.startRetrievalSpan(
                    agentSpan, AgentComponentVersions.RETRIEVAL);
            AgentContextSnapshot contextSnapshot = selected
                    ? contextEngine.buildSnapshot(
                            userId,
                            sessionId,
                            pageContext,
                            toolMap.values(),
                            historyBlock,
                            question.trim(),
                            taskPlan.evidencePolicy(),
                            taskPlan.retrievalQuery())
                    : contextEngine.buildSnapshot(
                            userId,
                            sessionId,
                            pageContext,
                            toolMap.values(),
                            historyBlock,
                            question.trim(),
                            false);
            if (selected) {
                contextSnapshot = contextSnapshot.withSystemPrompt(
                        bindSkillPrompt(
                                contextSnapshot.systemPrompt(),
                                selection,
                                taskPlan.evidencePolicy()));
                maxSteps = Math.min(maxSteps, selectedSkill.maxSteps());
            }
            trace.recordRetrieval(
                    contextSnapshot.retrievalStatuses(), contextSnapshot.evidenceSet());
            if (contextSnapshot.evidenceRequired() && contextSnapshot.evidenceSet().isEmpty()) {
                trace.recordCitationValidation(EvidenceSet.ValidationStatus.NO_EVIDENCE.name());
                trace.recordSkillValidation(selected ? "INSUFFICIENT_EVIDENCE" : "NOT_APPLICABLE");
                trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.NO_EVIDENCE);
                trace.markFailure(contextSnapshot.retrievalStatuses().containsValue("TIMEOUT")
                        ? AgentExecutionTrace.FailureType.RETRIEVAL_TIMEOUT
                        : AgentExecutionTrace.FailureType.RETRIEVAL_EMPTY);
                completeBoundaryResponse(
                        AgentExecutionTrace.OutcomeReason.NO_EVIDENCE,
                        selectedSkill == null ? "" : selectedSkill.id(),
                        "retrieval_empty",
                        question,
                        memory,
                        sink,
                        trace,
                        agentSpan);
                return;
            }
            AgentLoopRequest request = new AgentLoopRequest(
                    contextSnapshot.systemPrompt(),
                    question.trim(),
                    userId,
                    historyBlock,
                    toolMap,
                    maxSteps);
            AgentLoopResult result = loopExecutor.execute(request, trace, agentSpan, sink);
            if (result.status() == AgentLoopResult.Status.FINAL_READY) {
                long streamLlmMs = streamFinalAnswer(
                        sink,
                        question,
                        result.transcript(),
                        memory,
                        trace,
                        agentSpan,
                        result.finalStepIndex(),
                        contextSnapshot,
                        selectedSkill);
                trace.recordStep(
                        result.finalStepIndex(),
                        "final_answer",
                        result.finalDecisionLlmMs() + streamLlmMs,
                        0);
            } else {
                classifyLoopFailure(result.status(), trace);
            }
        } catch (RuntimeException e) {
            trace.terminateError();
            trace.markFailure(AgentExecutionTrace.FailureType.INTERNAL_ERROR);
            trace.setErrorMessage(AgentExecutionTrace.FailureType.INTERNAL_ERROR.name());
            throw e;
        } finally {
            finishRetrievalSpan(retrievalSpan, trace);
            finishSkillSpan(skillSpan, trace);
        }
    }

    private SkillSelector.SkillSelection selectSkill(
            long userId,
            String sessionId,
            String taskType,
            String question,
            String pageContext,
            Set<String> availableTools,
            AgentExecutionTrace trace) {
        try {
            SkillSelector.SkillSelection selection = null;
            if (skillRegistry != null && skillSelector != null) {
                Set<String> toolNames = Set.copyOf(availableTools);
                selection = skillSelector.select(
                        skillRegistry.enabled(),
                        new SkillExecutionContext(
                                userId, sessionId, taskType, question, pageContext, toolNames, toolNames));
            }
            String status = selection == null ? "DISABLED" : selection.status().name();
            SkillDefinition definition = selection == null ? null : selection.definition();
            trace.recordSkillSelection(
                    status,
                    definition == null ? null : definition.id(),
                    definition == null ? null : definition.version());
            return selection;
        } catch (RuntimeException exception) {
            trace.recordSkillSelection("ERROR", null, null);
            trace.markFailure(AgentExecutionTrace.FailureType.INTERNAL_ERROR);
            throw exception;
        }
    }

    private void finishSkillSpan(Observation span, AgentExecutionTrace trace) {
        if (span == null) {
            return;
        }
        AgentExecutionTrace.FailureType failure = trace.getFailureType();
        boolean skillFailure = failure == AgentExecutionTrace.FailureType.SKILL_NO_MATCH
                || failure == AgentExecutionTrace.FailureType.SKILL_VALIDATION_FAILED
                || (failure == AgentExecutionTrace.FailureType.INTERNAL_ERROR
                && "ERROR".equals(trace.getSkillSelectionStatus()));
        Map<String, String> low = new LinkedHashMap<>();
        low.put("component.version", AgentComponentVersions.SKILL_SELECTOR);
        low.put("status", skillFailure ? "error" : skillSpanStatus(trace));
        if (!trace.getSkillId().isBlank()) {
            low.put("skill.id", trace.getSkillId());
        }
        if (!trace.getSkillVersion().isBlank()) {
            low.put("skill.version", trace.getSkillVersion());
        }
        if (skillFailure) {
            low.put("error.type", failure.name());
        }
        Map<String, String> high = Map.of(
                "skill.selection.status", trace.getSkillSelectionStatus(),
                "skill.validation.status", trace.getSkillValidationStatus());
        if (skillFailure) {
            agentObservationService.finishSpanError(span, "skill_failed", low, high);
        } else {
            agentObservationService.finishSpan(span, low, high);
        }
    }

    private String skillSpanStatus(AgentExecutionTrace trace) {
        if (!"NOT_RUN".equals(trace.getSkillValidationStatus())) {
            return trace.getSkillValidationStatus().toLowerCase(java.util.Locale.ROOT);
        }
        return trace.getSkillSelectionStatus().toLowerCase(java.util.Locale.ROOT);
    }

    private void finishRetrievalSpan(Observation span, AgentExecutionTrace trace) {
        if (span == null) {
            return;
        }
        AgentExecutionTrace.FailureType failure = trace.getFailureType();
        boolean retrievalFailure = failure == AgentExecutionTrace.FailureType.RETRIEVAL_EMPTY
                || failure == AgentExecutionTrace.FailureType.RETRIEVAL_TIMEOUT
                || failure == AgentExecutionTrace.FailureType.CITATION_INVALID;
        boolean degraded = trace.getRetrievalStatuses().containsValue("FAILED")
                || trace.getRetrievalStatuses().containsValue("TIMEOUT");
        Map<String, String> low = new LinkedHashMap<>();
        low.put("component.version", AgentComponentVersions.RETRIEVAL);
        low.put("status", retrievalFailure ? "error"
                : degraded ? "degraded"
                : trace.getEvidenceCount() == 0 ? "empty" : "success");
        if (retrievalFailure) {
            low.put("error.type", failure.name());
        }
        Map<String, String> high = new LinkedHashMap<>();
        trace.getRetrievalStatuses().forEach((route, status) ->
                high.put("retrieval." + route + ".status", status));
        high.put("retrieval.evidence_count", String.valueOf(trace.getEvidenceCount()));
        high.put("retrieval.degraded", String.valueOf(degraded));
        high.put("retrieval.citation_validation", trace.getCitationValidationStatus());
        if (retrievalFailure) {
            agentObservationService.finishSpanError(span, "retrieval_failed", low, high);
        } else {
            agentObservationService.finishSpan(span, low, high);
        }
    }

    private boolean isSelected(SkillSelector.SkillSelection selection) {
        return selection != null && selection.status() == SkillSelector.Status.SELECTED;
    }

    private String bindSkillPrompt(
            String system,
            SkillSelector.SkillSelection selection,
            com.chtholly.agent.skill.EvidencePolicy evidencePolicy) {
        SkillDefinition definition = selection.definition();
        return system + "\n\n## 当前领域 Skill\n\n"
                + "skillId=" + definition.id() + "\n"
                + "skillVersion=" + definition.version() + "\n"
                + "outputType=" + definition.outputType() + "\n"
                + "evidencePolicy=" + evidencePolicy.name() + "\n"
                + "allowedTools=" + selection.allowedTools().stream().sorted()
                .collect(java.util.stream.Collectors.joining(",")) + "\n\n"
                + definition.instructionTemplate();
    }

    private void completeBoundaryResponse(
            AgentExecutionTrace.OutcomeReason reason,
            String skillId,
            String detail,
            String question,
            AgentConversationMemory memory,
            Consumer<AgentEvent> sink,
            AgentExecutionTrace trace,
            Observation agentSpan) {
        String fallback = boundaryFallback(reason, skillId);
        String system = characterSoulService.getSoulContent() + "\n\n"
                + "## 当前响应边界\n\n"
                + "reason=" + reason.name() + "\n"
                + "skillId=" + (skillId == null ? "" : skillId) + "\n"
                + "detail=" + (detail == null ? "" : detail) + "\n\n"
                + "只输出一段简短自然语言。遵循角色设定，但不要堆叠语气词或卖萌。"
                + "不得回答原任务、编造站内事实或生成引用；只说明当前边界并给出下一步。";
        String userPrompt = "请根据稳定原因码生成对用户可见的边界提示。";
        String answer = fallback;
        Observation llmSpan = agentObservationService.startLlmSpan(agentSpan, properties.getModel());
        long startedAt = System.currentTimeMillis();
        try {
            StringBuilder generated = new StringBuilder();
            llmInvoker.stream(system, userPrompt, 0.2, 192)
                    .doOnNext(chunk -> {
                        if (chunk != null) {
                            generated.append(chunk);
                        }
                    })
                    .blockLast();
            String candidate = truncateBoundaryAnswer(generated.toString());
            if (boundaryAnswerSafe(reason, candidate)) {
                answer = candidate;
            }
            trace.recordLlmCall(
                    0,
                    System.currentTimeMillis() - startedAt,
                    system.length() + userPrompt.length(),
                    answer.length(),
                    null);
            agentObservationService.finishSpan(
                    llmSpan,
                    AgentSpanAttributes.llm("ok"),
                    Map.of("response.boundary_reason", reason.name()));
        } catch (Exception exception) {
            long durationMs = System.currentTimeMillis() - startedAt;
            trace.recordLlmCall(
                    0,
                    durationMs,
                    system.length() + userPrompt.length(),
                    answer.length(),
                    null);
            if (isTimeout(exception)) {
                trace.markFailure(AgentExecutionTrace.FailureType.LLM_TIMEOUT);
            } else {
                trace.markFailure(AgentExecutionTrace.FailureType.INTERNAL_ERROR);
            }
            agentObservationService.finishSpanError(
                    llmSpan,
                    isTimeout(exception) ? "boundary_timeout" : "boundary_error",
                    AgentSpanAttributes.llm(isTimeout(exception) ? "timeout" : "error"),
                    Map.of("response.boundary_reason", reason.name()));
            log.warn("Agent boundary response fell back to safe copy: reason={}", reason, exception);
        }
        trace.terminateFinalAnswer(answer);
        emitThrottledDelta(sink, answer);
        emitFinal(sink, answer);
        if (memory != null) {
            memory.add(AgentTurn.user(question.trim()));
            memory.add(AgentTurn.assistant(answer));
        }
    }

    private String truncateBoundaryAnswer(String answer) {
        String normalized = answer == null ? "" : answer.strip();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 400);
    }

    private boolean boundaryAnswerSafe(
            AgentExecutionTrace.OutcomeReason reason,
            String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.matches("(?s).*\\[E\\d+].*")) {
            return false;
        }
        return switch (reason) {
            case NEEDS_CLARIFICATION ->
                    containsAny(candidate, "告诉", "提供", "贴", "哪", "什么", "？", "?");
            case NO_EVIDENCE ->
                    containsAny(candidate, "没有", "不足", "暂时", "找不到")
                            && containsAny(candidate, "资料", "证据", "依据");
            case INVALID_CITATION ->
                    containsAny(candidate, "不能", "无法", "对不上", "不可靠", "不一致")
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

    private String boundaryFallback(
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
            Observation agentSpan,
            int stepIndex,
            AgentContextSnapshot contextSnapshot,
            SkillDefinition selectedSkill) {
        String context = String.join("\n\n", transcript);
        String finalInstructions = agentDomainConfig.render(
                agentDomainConfig.systemPrompt().finalAnswerSystem(),
                "soul", characterSoulService.getSoulContent());
        String system = contextSnapshot.systemPrompt().isBlank()
                ? finalInstructions
                : contextSnapshot.systemPrompt() + "\n\n" + finalInstructions;
        String userPrompt = context + "\n\n" + agentDomainConfig.systemPrompt().finalAnswerPrompt();
        int inputChars = system.length() + userPrompt.length();

        int timeoutSec = Math.max(1, properties.getLlmTimeoutSeconds());
        Observation llmSpan = agentObservationService.startLlmSpan(agentSpan, properties.getModel());
        long streamStart = System.currentTimeMillis();
        AtomicLong firstTokenMs = new AtomicLong(-1);
        String answer;
        long streamMs;
        try {
            Flux<String> flux = llmInvoker.stream(system, userPrompt, 0.3, 1024);

            StringBuilder full = new StringBuilder();
            flux.doOnNext(chunk -> {
                if (chunk != null && !chunk.isEmpty()) {
                    firstTokenMs.compareAndSet(-1, System.currentTimeMillis() - trace.getStartedAtMs());
                    full.append(chunk);
                }
            }).blockLast();

            String candidate = truncateAnswer(full.toString());
            EvidenceSet.ValidationResult evidenceValidation = contextSnapshot.evidenceSet()
                    .validate(candidate, contextSnapshot.evidenceRequired());
            trace.recordCitationValidation(evidenceValidation.status().name());
            if (evidenceValidation.status() == EvidenceSet.ValidationStatus.UNKNOWN_CITATION
                    || evidenceValidation.status() == EvidenceSet.ValidationStatus.MISSING_CITATION) {
                trace.markFailure(AgentExecutionTrace.FailureType.CITATION_INVALID);
                trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.INVALID_CITATION);
            } else if (evidenceValidation.status() == EvidenceSet.ValidationStatus.NO_EVIDENCE) {
                trace.markFailure(AgentExecutionTrace.FailureType.RETRIEVAL_EMPTY);
                trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.NO_EVIDENCE);
            }
            answer = evidenceValidation.safeAnswer();
            if (trace.getOutcomeReason() == AgentExecutionTrace.OutcomeReason.INVALID_CITATION
                    || trace.getOutcomeReason() == AgentExecutionTrace.OutcomeReason.NO_EVIDENCE) {
                streamMs = System.currentTimeMillis() - streamStart;
                Long ttft = firstTokenMs.get() >= 0 ? firstTokenMs.get() : null;
                trace.recordLlmCall(stepIndex, streamMs, inputChars, candidate.length(), ttft);
                agentObservationService.finishSpan(
                        llmSpan,
                        AgentSpanAttributes.llm("ok"),
                        Map.of());
                completeBoundaryResponse(
                        trace.getOutcomeReason(),
                        selectedSkill == null ? "" : selectedSkill.id(),
                        evidenceValidation.status().name(),
                        question,
                        memory,
                        sink,
                        trace,
                        agentSpan);
                return streamMs;
            }
            if (selectedSkill != null && skillOutputValidator != null) {
                SkillOutputValidator.SkillValidationResult skillValidation = skillOutputValidator.validate(
                        selectedSkill,
                        answer,
                        contextSnapshot.evidenceSet(),
                        question,
                        contextSnapshot.evidenceRequired());
                trace.recordSkillValidation(skillValidation.status().name());
                if (skillValidation.status() == SkillOutputValidator.Status.CITATION_INVALID) {
                    trace.markFailure(AgentExecutionTrace.FailureType.CITATION_INVALID);
                    trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.INVALID_CITATION);
                    streamMs = System.currentTimeMillis() - streamStart;
                    Long ttft = firstTokenMs.get() >= 0 ? firstTokenMs.get() : null;
                    trace.recordLlmCall(stepIndex, streamMs, inputChars, candidate.length(), ttft);
                    agentObservationService.finishSpan(
                            llmSpan,
                            AgentSpanAttributes.llm("ok"),
                            Map.of());
                    completeBoundaryResponse(
                            AgentExecutionTrace.OutcomeReason.INVALID_CITATION,
                            selectedSkill.id(),
                            String.join(",", skillValidation.errors()),
                            question,
                            memory,
                            sink,
                            trace,
                            agentSpan);
                    return streamMs;
                }
                if (skillValidation.status() != SkillOutputValidator.Status.VALID
                        && skillValidation.status() != SkillOutputValidator.Status.INSUFFICIENT_EVIDENCE
                        && trace.getFailureType() == AgentExecutionTrace.FailureType.NONE) {
                    trace.markFailure(AgentExecutionTrace.FailureType.SKILL_VALIDATION_FAILED);
                }
                answer = skillValidation.output();
            } else {
                trace.recordSkillValidation("NOT_APPLICABLE");
            }
            streamMs = System.currentTimeMillis() - streamStart;
            Long ttft = firstTokenMs.get() >= 0 ? firstTokenMs.get() : null;
            trace.recordLlmCall(stepIndex, streamMs, inputChars, answer.length(), ttft);
        } catch (Exception e) {
            streamMs = System.currentTimeMillis() - streamStart;
            Long ttft = firstTokenMs.get() >= 0 ? firstTokenMs.get() : null;
            trace.recordLlmCall(stepIndex, streamMs, inputChars, 0, ttft);
            if (isTimeout(e)) {
                agentObservationService.finishSpanError(
                        llmSpan,
                        "stream_timeout",
                        AgentSpanAttributes.llm("timeout"),
                        Map.of());
                log.warn("Agent streaming answer timed out (>{}s)", timeoutSec);
                trace.terminateTimeout();
                trace.markFailure(AgentExecutionTrace.FailureType.LLM_TIMEOUT);
                trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.MODEL_FAILURE);
                trace.setErrorMessage(agentDomainConfig.errors().responseTimeout());
                emitError(sink, agentDomainConfig.errors().responseTimeout());
                return streamMs;
            }
            agentObservationService.finishSpanError(
                    llmSpan,
                    "stream_error",
                    AgentSpanAttributes.llm("error"),
                    Map.of());
            log.warn("Agent streaming answer failed: {}", e.getMessage());
            trace.terminateError();
            trace.markFailure(AgentExecutionTrace.FailureType.INTERNAL_ERROR);
            trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.MODEL_FAILURE);
            trace.setErrorMessage(agentDomainConfig.errors().responseFailed());
            emitError(sink, agentDomainConfig.errors().responseFailed());
            return streamMs;
        }

        agentObservationService.finishSpan(
                llmSpan,
                AgentSpanAttributes.llm("ok"),
                Map.of());
        trace.terminateFinalAnswer(answer);
        if (!answer.isBlank()) {
            emitThrottledDelta(sink, answer);
        }
        emitFinal(sink, answer);
        if (memory != null && !answer.isBlank()) {
            memory.add(AgentTurn.user(question.trim()));
            memory.add(AgentTurn.assistant(answer));
        }
        return streamMs;
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

    private void classifyLoopFailure(
            AgentLoopResult.Status status,
            AgentExecutionTrace trace) {
        switch (status) {
            case LLM_TIMEOUT -> {
                trace.markFailure(AgentExecutionTrace.FailureType.LLM_TIMEOUT);
                trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.MODEL_FAILURE);
            }
            case TOOL_INTERRUPTED -> trace.markFailure(AgentExecutionTrace.FailureType.TOOL_FAILED);
            case LLM_ERROR, LLM_INTERRUPTED -> {
                trace.markFailure(AgentExecutionTrace.FailureType.INTERNAL_ERROR);
                trace.recordOutcomeReason(AgentExecutionTrace.OutcomeReason.MODEL_FAILURE);
            }
            case MAX_STEPS -> trace.markFailure(AgentExecutionTrace.FailureType.INTERNAL_ERROR);
            case FINAL_READY -> {
                // Handled by the final-answer branch.
            }
        }
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

}
