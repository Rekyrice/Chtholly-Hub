package com.chtholly.agent.web;

/**
 * Represents a stable, user-safe failure raised by the web research boundary.
 */
public class WebResearchException extends RuntimeException {

    private final String code;
    private final String userMessage;

    /**
     * Creates a web research failure.
     *
     * @param code stable machine-readable error code
     * @param userMessage safe message suitable for a user-facing response
     */
    public WebResearchException(String code, String userMessage) {
        super(userMessage);
        this.code = code;
        this.userMessage = userMessage;
    }

    /**
     * Creates a web research failure with an internal cause.
     *
     * @param code stable machine-readable error code
     * @param userMessage safe message suitable for a user-facing response
     * @param cause internal failure cause
     */
    public WebResearchException(String code, String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.code = code;
        this.userMessage = userMessage;
    }

    /**
     * Returns the stable error code.
     *
     * @return error code
     */
    public String code() {
        return code;
    }

    /**
     * Returns the user-safe error message.
     *
     * @return user-safe message
     */
    public String userMessage() {
        return userMessage;
    }
}
