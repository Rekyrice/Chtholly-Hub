package com.chtholly.agent.response;

/**
 * Indicates that final-answer output still violates the client-visible protocol after repair.
 */
public final class AgentInvalidFinalAnswerException extends RuntimeException {

    /** Creates a protocol failure with a stable code. */
    public AgentInvalidFinalAnswerException(String code) {
        super(code);
    }

    /** Creates a protocol failure with its underlying model error. */
    public AgentInvalidFinalAnswerException(String code, Throwable cause) {
        super(code, cause);
    }
}
