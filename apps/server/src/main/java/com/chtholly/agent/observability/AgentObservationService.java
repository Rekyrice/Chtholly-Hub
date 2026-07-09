package com.chtholly.agent.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agent 模块 Micrometer Observation 封装：生成 Agent → LLM → Tool 三级 span。
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentObservationService {

    public static final String SPAN_AGENT = "agent.run";
    public static final String SPAN_LLM = "agent.llm.call";
    public static final String SPAN_TOOL = "agent.tool.execute";

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
                .lowCardinalityKeyValue("agent.user_id", String.valueOf(userId))
                .lowCardinalityKeyValue("agent.correlation_id", safe(correlationId))
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

    /**
     * 结束 span 并附加结果属性。
     */
    public void finishSpan(Observation observation, Map<String, String> attributes) {
        if (observation == null) {
            return;
        }
        applyAttributes(observation, attributes);
        observation.stop();
    }

    /**
     * 以 error 状态结束 span。
     */
    public void finishSpanError(Observation observation, String message, Map<String, String> attributes) {
        if (observation == null) {
            return;
        }
        observation.error(new AgentObservationException(message));
        applyAttributes(observation, attributes);
        observation.stop();
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
