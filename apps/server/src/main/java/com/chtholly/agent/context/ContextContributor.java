package com.chtholly.agent.context;

/** Produces one ordered section of the agent system prompt. */
public interface ContextContributor {

    /** @return stable lowercase contributor name */
    String name();

    /** @return unique prompt assembly order */
    int order();

    /**
     * Renders this contributor's prompt fragment.
     *
     * @param request immutable context request
     * @return rendered contribution
     */
    ContextContribution contribute(ContextRequest request);
}
