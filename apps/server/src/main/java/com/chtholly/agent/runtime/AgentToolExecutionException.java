package com.chtholly.agent.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Controlled tool failure carrying a stable machine code and safe user-facing message. */
public class AgentToolExecutionException extends RuntimeException {

    private final String errorCode;
    private final String userMessage;
    private final Map<String, Object> diagnosticAttributes;

    /**
     * Creates a controlled tool failure.
     *
     * @param errorCode stable low-cardinality error code
     * @param userMessage safe observation shown to the model and user
     */
    public AgentToolExecutionException(String errorCode, String userMessage) {
        this(errorCode, userMessage, Map.of());
    }

    /** Creates a controlled failure with administrator-only structured diagnostics. */
    public AgentToolExecutionException(
            String errorCode,
            String userMessage,
            Map<String, Object> diagnosticAttributes) {
        this(errorCode, userMessage, diagnosticAttributes, null);
    }

    /** Creates a controlled failure while preserving its internal cause for administrator Trace. */
    public AgentToolExecutionException(
            String errorCode,
            String userMessage,
            Map<String, Object> diagnosticAttributes,
            Throwable cause) {
        super(require(errorCode, "errorCode") + ": " + require(userMessage, "userMessage"), cause);
        this.errorCode = errorCode.strip();
        this.userMessage = userMessage.strip();
        this.diagnosticAttributes = diagnosticAttributes == null || diagnosticAttributes.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(diagnosticAttributes));
    }

    public String errorCode() {
        return errorCode;
    }

    public String userMessage() {
        return userMessage;
    }

    public Map<String, Object> diagnosticAttributes() {
        return diagnosticAttributes;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
