package com.chtholly.agent.runtime;

import com.chtholly.agent.observability.AgentExecutionTrace;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Shared high-cardinality attributes for agent runtime observation spans. */
public final class AgentSpanAttributes {

    private AgentSpanAttributes() {
    }

    /** Returns terminal attributes for the overall agent span. */
    public static Map<String, String> agent(AgentExecutionTrace trace) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("agent.status", trace.getTerminatedBy());
        attributes.put("agent.total_steps", String.valueOf(trace.getTotalSteps()));
        attributes.put("agent.llm_calls", String.valueOf(trace.getLlmCalls()));
        attributes.put("agent.duration_ms", String.valueOf(
                trace.getDurationMs() == null ? 0 : trace.getDurationMs()));
        return attributes;
    }

    /** Returns timing, size, and status attributes for an LLM span. */
    public static Map<String, String> llm(
            long durationMs,
            int inputChars,
            int outputChars,
            String status) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("llm.duration_ms", String.valueOf(durationMs));
        attributes.put("llm.input_chars", String.valueOf(inputChars));
        attributes.put("llm.output_chars", String.valueOf(outputChars));
        attributes.put("llm.status", status);
        return attributes;
    }

    /** Returns identity, timing, and outcome attributes for a tool span. */
    public static Map<String, String> tool(
            String toolName,
            long durationMs,
            AgentToolResult.Status status) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tool.name", toolName);
        attributes.put("tool.duration_ms", String.valueOf(durationMs));
        attributes.put("tool.success", String.valueOf(status == AgentToolResult.Status.SUCCESS));
        attributes.put("tool.status", status.name().toLowerCase(Locale.ROOT));
        return attributes;
    }
}
