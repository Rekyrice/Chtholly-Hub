package com.chtholly.agent.runtime;

import java.util.List;

/**
 * Immutable outcome of one bounded ReAct loop execution.
 *
 * @param status loop terminal status
 * @param transcript accumulated prompt transcript
 * @param errorMessage user-visible terminal error, or {@code null} on success
 */
public record AgentLoopResult(Status status, List<String> transcript, String errorMessage) {
    public AgentLoopResult {
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
    }

    /** Bounded loop terminal outcomes. */
    public enum Status {
        FINAL_READY,
        LLM_TIMEOUT,
        LLM_ERROR,
        TOOL_INTERRUPTED,
        MAX_STEPS
    }
}
