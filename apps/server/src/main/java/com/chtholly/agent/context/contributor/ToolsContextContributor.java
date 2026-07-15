package com.chtholly.agent.context.contributor;

import com.chtholly.agent.context.ContextContribution;
import com.chtholly.agent.context.ContextContributor;
import com.chtholly.agent.context.ContextOrder;
import com.chtholly.agent.context.ContextRequest;
import com.chtholly.agent.context.PromptTailRenderer;
import org.springframework.stereotype.Component;

/** Renders the ReAct tool inventory and protocol. */
@Component
public class ToolsContextContributor implements ContextContributor {

    private final PromptTailRenderer renderer = new PromptTailRenderer();

    @Override
    public String name() {
        return "tools";
    }

    @Override
    public int order() {
        return ContextOrder.TOOLS;
    }

    @Override
    public ContextContribution contribute(ContextRequest request) {
        return new ContextContribution(name(), order(), renderer.renderTools(request.tools()), false);
    }
}
