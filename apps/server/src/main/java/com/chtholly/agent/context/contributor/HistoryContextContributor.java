package com.chtholly.agent.context.contributor;

import com.chtholly.agent.context.ContextContribution;
import com.chtholly.agent.context.ContextContributor;
import com.chtholly.agent.context.ContextOrder;
import com.chtholly.agent.context.ContextRequest;
import com.chtholly.agent.context.PromptTailRenderer;
import org.springframework.stereotype.Component;

/** Renders formatted conversation history with episodic-anchor fallback. */
@Component
public class HistoryContextContributor implements ContextContributor {

    private final PromptTailRenderer renderer = new PromptTailRenderer();

    @Override
    public String name() {
        return "history";
    }

    @Override
    public int order() {
        return ContextOrder.HISTORY;
    }

    @Override
    public ContextContribution contribute(ContextRequest request) {
        return new ContextContribution(name(), order(),
                renderer.renderHistory(request.conversationHistory(), request.anchors().episodic()), false);
    }
}
