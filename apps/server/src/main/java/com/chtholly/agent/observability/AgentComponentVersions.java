package com.chtholly.agent.observability;

/** Stable component identifiers persisted with Agent traces and replay fixtures. */
public final class AgentComponentVersions {

    public static final String PROMPT = "agent-prompt-v1";
    public static final String SKILL_SELECTOR = "skill-selector-v1";
    public static final String RETRIEVAL = "document-rrf-v1";
    public static final String TOOLS = "agent-tool-v1";
    public static final String TRACE_SCHEMA = "agent-trace-v1";

    private AgentComponentVersions() {
    }
}
