package com.chtholly.agent.runtime;

/**
 * Signals that a thread waiting for a bounded agent stage was interrupted by its caller.
 */
public final class AgentStageInterruptedException extends IllegalStateException {

    /**
     * Creates an interruption failure that retains the affected stage and original cause.
     *
     * @param stage stable agent stage name
     * @param cause caller interruption
     */
    public AgentStageInterruptedException(String stage, InterruptedException cause) {
        super("Agent stage interrupted while waiting: " + stage, cause);
    }
}
