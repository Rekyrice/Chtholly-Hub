package com.chtholly.agent.context.contributor;

import com.chtholly.agent.context.ContextContribution;
import com.chtholly.agent.context.ContextContributor;
import com.chtholly.agent.context.ContextOrder;
import com.chtholly.agent.context.ContextRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/** Renders learned procedural rules from the anchor snapshot. */
@Component
public class ProceduralContextContributor implements ContextContributor {

    @Override
    public String name() {
        return "procedural";
    }

    @Override
    public int order() {
        return ContextOrder.PROCEDURAL;
    }

    @Override
    public ContextContribution contribute(ContextRequest request) {
        List<String> procedural = request.anchors().procedural();
        if (procedural == null || procedural.isEmpty()) {
            return ContextContribution.empty(name(), order(), false);
        }
        StringBuilder prompt = new StringBuilder("## 你学到的行为规则\n\n");
        for (String rule : procedural) {
            if (rule != null && !rule.isBlank()) {
                prompt.append("- ").append(rule.trim()).append('\n');
            }
        }
        return new ContextContribution(name(), order(), prompt.toString(), false);
    }
}
