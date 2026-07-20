package com.chtholly.agent.context;

import com.chtholly.agent.evidence.Evidence;

import java.util.List;
import java.util.Map;

/** Rendered prompt fragment produced by one contributor. */
public record ContextContribution(
        String name,
        int order,
        String content,
        boolean degraded,
        List<Evidence> evidence,
        boolean evidenceRequired,
        Map<String, String> retrievalStatuses) {

    public ContextContribution {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        retrievalStatuses = retrievalStatuses == null ? Map.of() : Map.copyOf(retrievalStatuses);
    }

    public ContextContribution(String name, int order, String content, boolean degraded) {
        this(name, order, content, degraded, List.of(), false, Map.of());
    }

    public ContextContribution(
            String name,
            int order,
            String content,
            boolean degraded,
            List<Evidence> evidence,
            boolean evidenceRequired) {
        this(name, order, content, degraded, evidence, evidenceRequired, Map.of());
    }

    /**
     * Creates an empty contribution while retaining degradation metadata.
     *
     * @param name contributor name
     * @param order contributor order
     * @param degraded whether rendering degraded
     * @return empty contribution
     */
    public static ContextContribution empty(String name, int order, boolean degraded) {
        return new ContextContribution(name, order, "", degraded, List.of(), false, Map.of());
    }

    /**
     * Returns whether this contribution has no renderable content.
     *
     * @return true when content is null or blank
     */
    public boolean isEmpty() {
        return content == null || content.isBlank();
    }
}
