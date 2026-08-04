package com.chtholly.agent.memory;

/**
 * Indicates that conversation memory rejected or could not confirm an exchange write.
 */
public final class AgentMemoryWriteException extends RuntimeException {

    /**
     * Creates a failure with a stable storage rejection code.
     *
     * @param code stable memory failure code
     */
    public AgentMemoryWriteException(String code) {
        super(code == null || code.isBlank() ? "MEMORY_WRITE_FAILED" : code);
    }
}
