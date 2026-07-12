package com.chtholly.agent.runtime;

import java.util.List;

/**
 * Immutable outcome of one bounded ReAct loop execution.
 *
 * @param status loop terminal status
 * @param transcript accumulated prompt transcript
 * @param errorMessage user-visible terminal error, or {@code null} on success
 * @param finalStepIndex final decision step index, or {@code -1} for terminal errors
 * @param finalDecisionLlmMs final decision model duration to merge with outer streaming time
 */
public record AgentLoopResult(
        Status status,
        List<String> transcript,
        String errorMessage,
        int finalStepIndex,
        long finalDecisionLlmMs
) {
    public AgentLoopResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
        if (status == Status.FINAL_READY) {
            if (errorMessage != null || finalStepIndex < 0 || finalDecisionLlmMs < 0) {
                throw new IllegalArgumentException("invalid final-ready trace metadata");
            }
        } else if (finalStepIndex != -1
                || finalDecisionLlmMs != 0
                || errorMessage == null
                || errorMessage.isBlank()) {
            throw new IllegalArgumentException("invalid terminal error metadata");
        }
    }

    /**
     * Creates a successful loop result whose final step will be recorded by the outer orchestrator.
     *
     * @param transcript accumulated prompt transcript
     * @param finalStepIndex zero-based final decision step index
     * @param finalDecisionLlmMs final decision model duration in milliseconds
     * @return final-ready result carrying trace merge metadata
     */
    public static AgentLoopResult finalReady(
            List<String> transcript,
            int finalStepIndex,
            long finalDecisionLlmMs) {
        return new AgentLoopResult(
                Status.FINAL_READY,
                transcript,
                null,
                finalStepIndex,
                finalDecisionLlmMs);
    }

    /**
     * Creates a non-final terminal result without final-step trace metadata.
     *
     * @param status non-final terminal status
     * @param transcript accumulated prompt transcript
     * @param errorMessage user-visible terminal error
     * @return terminal result
     */
    public static AgentLoopResult terminal(
            Status status,
            List<String> transcript,
            String errorMessage) {
        return new AgentLoopResult(status, transcript, errorMessage, -1, 0);
    }

    /** Bounded loop terminal outcomes. */
    public enum Status {
        FINAL_READY,
        LLM_TIMEOUT,
        LLM_ERROR,
        LLM_INTERRUPTED,
        TOOL_INTERRUPTED,
        MAX_STEPS
    }
}
