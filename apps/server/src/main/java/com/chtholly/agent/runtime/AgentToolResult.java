package com.chtholly.agent.runtime;

import com.chtholly.agent.observability.AgentToolDiagnostics;
import com.chtholly.agent.evidence.Evidence;

import java.util.List;
import java.util.Objects;

/**
 * Structured result of one agent tool execution.
 *
 * @param observation user-visible observation text
 * @param status execution outcome category
 * @param errorCode stable low-cardinality error code, empty on success
 * @param diagnostics bounded diagnostics safe for trace persistence
 * @param evidence immutable evidence discovered by a successful tool execution
 */
public record AgentToolResult(
        String observation,
        Status status,
        String errorCode,
        AgentToolDiagnostics diagnostics,
        List<Evidence> evidence) {

    public AgentToolResult {
        observation = observation == null ? "" : observation;
        status = Objects.requireNonNull(status, "status");
        errorCode = errorCode == null ? "" : errorCode;
        diagnostics = diagnostics == null
                ? AgentToolDiagnostics.fallback("unknown", observation).withErrorCode(errorCode)
                : diagnostics;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (status != Status.SUCCESS) {
            evidence = List.of();
        }
    }

    /** Backward-compatible constructor for call sites that do not yet provide evidence. */
    public AgentToolResult(
            String observation,
            Status status,
            String errorCode,
            AgentToolDiagnostics diagnostics) {
        this(observation, status, errorCode, diagnostics, List.of());
    }

    /** Backward-compatible constructor for call sites that do not yet provide diagnostics. */
    public AgentToolResult(String observation, Status status) {
        this(observation, status, "", AgentToolDiagnostics.fallback("unknown", observation), List.of());
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
