package com.chtholly.agent.runtime;

/**
 * Structured result of one agent tool execution.
 *
 * @param observation user-visible observation text
 * @param status execution outcome category
 */
public record AgentToolResult(String observation, Status status) {

    /** Tool execution outcome categories. */
    public enum Status {
        SUCCESS,
        VALIDATION_ERROR,
        TIMEOUT,
        ERROR,
        INTERRUPTED
    }
}
