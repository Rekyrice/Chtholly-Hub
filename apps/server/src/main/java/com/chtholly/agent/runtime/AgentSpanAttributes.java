package com.chtholly.agent.runtime;

import com.chtholly.agent.observability.AgentExecutionTrace;

import java.util.Map;

/** Shared bounded outcome attributes for Agent runtime observation spans. */
public final class AgentSpanAttributes {

    private AgentSpanAttributes() {
    }

    /** Returns terminal attributes for the overall agent span. */
    public static Map<String, String> agent(AgentExecutionTrace trace) {
        return Map.of("status", trace.getTerminatedBy());
    }

    /** Returns the bounded terminal status for an LLM span. */
    public static Map<String, String> llm(String status) {
        return Map.of("status", status);
    }

    /** Returns the bounded terminal status for a tool span. */
    public static Map<String, String> tool(AgentToolResult.Status status) {
        return Map.of("status", status.name().toLowerCase(java.util.Locale.ROOT));
    }
}
