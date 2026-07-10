package com.chtholly.agent.config;

/** User-visible errors emitted by the agent runtime. */
public record AgentErrorMessages(
        String questionEmpty,
        String modelResponseTimeout,
        String modelCallFailed,
        String responseTimeout,
        String responseFailed,
        String maxSteps,
        String unknownTool,
        String toolFailed,
        String toolInterrupted,
        String noResult
) { }
