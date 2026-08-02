package com.chtholly.agent.runtime;

import com.chtholly.agent.evidence.Evidence;

import java.util.List;

/** Immutable detailed output returned by an agent tool execution. */
public record AgentToolOutput(String observation, List<Evidence> evidence) {

    public AgentToolOutput {
        observation = observation == null ? "" : observation;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /** Creates a text-only output for legacy tools. */
    public AgentToolOutput(String observation) {
        this(observation, List.of());
    }
}
