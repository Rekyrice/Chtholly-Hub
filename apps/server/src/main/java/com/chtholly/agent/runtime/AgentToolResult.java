package com.chtholly.agent.runtime;

import com.chtholly.agent.observability.AgentToolDiagnostics;

import java.util.Objects;

/**
 * Structured result of one agent tool execution.
 *
 * @param observation user-visible observation text
 * @param status execution outcome category
 * @param errorCode stable low-cardinality error code, empty on success
 * @param diagnostics bounded diagnostics safe for trace persistence
 */
public record AgentToolResult(
        String observation,
        Status status,
        String errorCode,
        AgentToolDiagnostics diagnostics) {

    public AgentToolResult {
        observation = observation == null ? "" : observation;
        status = Objects.requireNonNull(status, "status");
        errorCode = errorCode == null ? "" : errorCode;
        diagnostics = diagnostics == null
                ? AgentToolDiagnostics.fallback("unknown", observation).withErrorCode(errorCode)
                : diagnostics;
    }

    /** Backward-compatible constructor for call sites that do not yet provide diagnostics. */
    public AgentToolResult(String observation, Status status) {
        this(observation, status, "", AgentToolDiagnostics.fallback("unknown", observation));
    }

    /** Tool execution outcome categories. */
    public enum Status {
        SUCCESS,
        VALIDATION_ERROR,
        TIMEOUT,
        ERROR,
        INTERRUPTED
    }
}
