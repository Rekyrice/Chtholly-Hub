package com.chtholly.agent.runtime;

import com.chtholly.agent.evidence.EvidenceSet;

import java.util.List;

/**
 * Immutable outcome of one bounded ReAct loop execution.
 *
 * @param status loop terminal status
 * @param transcript accumulated prompt transcript
 * @param errorMessage user-visible terminal error, or {@code null} on success
 * @param finalStepIndex final decision step index, or {@code -1} for terminal errors
 * @param finalDecisionLlmMs final decision model duration to merge with outer streaming time
 * @param evidenceSet immutable final evidence snapshot, including successful dynamic evidence
 * @param evidenceRequired whether final output requires evidence-backed citations
 */
public record AgentLoopResult(
        Status status,
        List<String> transcript,
        String errorMessage,
        int finalStepIndex,
        long finalDecisionLlmMs,
        EvidenceSet evidenceSet,
        boolean evidenceRequired
) {
    public AgentLoopResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
        evidenceSet = evidenceSet == null ? EvidenceSet.empty() : evidenceSet;
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

    /** Backward-compatible canonical constructor without evidence state. */
    public AgentLoopResult(
            Status status,
            List<String> transcript,
            String errorMessage,
            int finalStepIndex,
            long finalDecisionLlmMs) {
        this(status, transcript, errorMessage, finalStepIndex, finalDecisionLlmMs,
                EvidenceSet.empty(), false);
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
                finalDecisionLlmMs,
                EvidenceSet.empty(),
                false);
    }

    /** Creates a successful result with its final evidence snapshot. */
    public static AgentLoopResult finalReady(
            List<String> transcript,
            int finalStepIndex,
            long finalDecisionLlmMs,
            EvidenceSet evidenceSet,
            boolean evidenceRequired) {
        return new AgentLoopResult(
                Status.FINAL_READY,
                transcript,
                null,
                finalStepIndex,
                finalDecisionLlmMs,
                evidenceSet,
                evidenceRequired);
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
        return new AgentLoopResult(
                status, transcript, errorMessage, -1, 0, EvidenceSet.empty(), false);
    }

    /** Creates a terminal result while preserving the final evidence snapshot. */
    public static AgentLoopResult terminal(
            Status status,
            List<String> transcript,
            String errorMessage,
            EvidenceSet evidenceSet,
            boolean evidenceRequired) {
        return new AgentLoopResult(
                status, transcript, errorMessage, -1, 0, evidenceSet, evidenceRequired);
    }

    /** Bounded loop terminal outcomes. */
    public enum Status {
        FINAL_READY,
        LLM_TIMEOUT,
        LLM_ERROR,
        LLM_INTERRUPTED,
        TOOL_INTERRUPTED,
        TURN_TIMEOUT,
        CANCELLED,
        MAX_STEPS
    }
}
