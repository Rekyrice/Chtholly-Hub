package com.chtholly.agent.context;

/** Rendered prompt fragment produced by one contributor. */
public record ContextContribution(String name, int order, String content, boolean degraded) {

    /**
     * Creates an empty contribution while retaining degradation metadata.
     *
     * @param name contributor name
     * @param order contributor order
     * @param degraded whether rendering degraded
     * @return empty contribution
     */
    public static ContextContribution empty(String name, int order, boolean degraded) {
        return new ContextContribution(name, order, "", degraded);
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
