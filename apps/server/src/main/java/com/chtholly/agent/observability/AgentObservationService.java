package com.chtholly.agent.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Agent 模块 Micrometer Observation 封装，统一约束运行、检索、Skill 与草稿 Span 属性。
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentObservationService {

    public static final String SPAN_AGENT = "agent.run";
    public static final String SPAN_LLM = "agent.llm.call";
    public static final String SPAN_TOOL = "agent.tool.execute";
    public static final String SPAN_SKILL = "agent.skill.select";
    public static final String SPAN_RETRIEVAL = "agent.retrieval.search";
    public static final String SPAN_DRAFT_PREVIEW = "agent.draft.preview";
    public static final String SPAN_DRAFT_APPLY = "agent.draft.apply";
    private static final Set<String> APPROVED_LOW_CARDINALITY_KEYS = Set.of(
            "skill.id",
            "skill.version",
            "llm.model",
            "retrieval.strategy",
            "component.version",
            "status",
            "error.type",
            "tool.name");

    private final ObservationRegistry observationRegistry;

    public AgentObservationService(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    /**
     * 创建 Agent 级 parent span。
     */
    public Observation startAgentSpan(String correlationId, long userId) {
        return Observation.createNotStarted(SPAN_AGENT, observationRegistry)
                .contextualName(SPAN_AGENT)
                .highCardinalityKeyValue("agent.correlation_id", safe(correlationId))
                .start();
    }

    /**
     * 创建 LLM 子 span。
     */
    public Observation startLlmSpan(Observation parentObservation, String model) {
        return Observation.createNotStarted(SPAN_LLM, observationRegistry)
                .parentObservation(parentObservation)
                .contextualName(SPAN_LLM)
                .lowCardinalityKeyValue("llm.model", safe(model))
                .start();
    }

    /**
     * 创建 Tool 子 span。
     */
    public Observation startToolSpan(Observation parentObservation, String toolName) {
        return Observation.createNotStarted(SPAN_TOOL, observationRegistry)
                .parentObservation(parentObservation)
                .contextualName(SPAN_TOOL)
                .lowCardinalityKeyValue("tool.name", safe(toolName))
                .start();
    }

    public Observation startSkillSpan(Observation parentObservation) {
        return startChild(SPAN_SKILL, parentObservation);
    }

    public Observation startRetrievalSpan(Observation parentObservation, String strategyVersion) {
        return startChild(SPAN_RETRIEVAL, parentObservation)
                .lowCardinalityKeyValue("retrieval.strategy", safe(strategyVersion));
    }

    public Observation startRetrievalSpan(String strategyVersion) {
        return startRetrievalSpan(observationRegistry.getCurrentObservation(), strategyVersion);
    }

    public Observation startDraftPreviewSpan(Observation parentObservation, String skillVersion) {
        return startChild(SPAN_DRAFT_PREVIEW, parentObservation)
                .lowCardinalityKeyValue("skill.id", "draft-edit")
                .lowCardinalityKeyValue("skill.version", safe(skillVersion));
    }

    public Observation startDraftPreviewSpan(String skillVersion) {
        return startDraftPreviewSpan(observationRegistry.getCurrentObservation(), skillVersion);
    }

    public Observation startDraftApplySpan(Observation parentObservation, String skillVersion) {
        return startChild(SPAN_DRAFT_APPLY, parentObservation)
                .lowCardinalityKeyValue("skill.id", "draft-edit")
                .lowCardinalityKeyValue("skill.version", safe(skillVersion));
    }

    public Observation startDraftApplySpan(String skillVersion) {
        return startDraftApplySpan(observationRegistry.getCurrentObservation(), skillVersion);
    }

    /**
     * 结束 span 并附加结果属性。
     */
    public void finishSpan(Observation observation, Map<String, String> attributes) {
        finishSpan(observation, Map.of(), attributes);
    }

    /** Finishes a span with an explicit bounded low-cardinality set and diagnostic attributes. */
    public void finishSpan(
            Observation observation,
            Map<String, String> lowCardinalityAttributes,
            Map<String, String> highCardinalityAttributes) {
        if (observation == null) {
            return;
        }
        applyLowCardinalityAttributes(observation, lowCardinalityAttributes);
        applyAttributes(observation, highCardinalityAttributes);
        observation.stop();
    }

    /**
     * 以 error 状态结束 span。
     */
    public void finishSpanError(Observation observation, String message, Map<String, String> attributes) {
        finishSpanError(observation, message, Map.of(), attributes);
    }

    public void finishSpanError(
            Observation observation,
            String message,
            Map<String, String> lowCardinalityAttributes,
            Map<String, String> highCardinalityAttributes) {
        if (observation == null) {
            return;
        }
        observation.error(new AgentObservationException(message));
        applyLowCardinalityAttributes(observation, lowCardinalityAttributes);
        applyAttributes(observation, highCardinalityAttributes);
        observation.stop();
    }

    private Observation startChild(String name, Observation parentObservation) {
        return Observation.createNotStarted(name, observationRegistry)
                .parentObservation(parentObservation)
                .contextualName(name)
                .start();
    }

    private static void applyLowCardinalityAttributes(
            Observation observation,
            Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        attributes.forEach((key, value) -> {
            if (APPROVED_LOW_CARDINALITY_KEYS.contains(key)) {
                observation.lowCardinalityKeyValue(key, value == null ? "" : value);
            }
        });
    }

    private static void applyAttributes(Observation observation, Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        attributes.forEach((key, value) ->
                observation.highCardinalityKeyValue(key, value == null ? "" : value));
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /** Observation.error 使用的轻量异常类型。 */
    static final class AgentObservationException extends RuntimeException {
        AgentObservationException(String message) {
            super(message);
        }
    }
}
