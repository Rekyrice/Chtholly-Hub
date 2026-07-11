package com.chtholly.agent.graph;

import com.chtholly.agent.config.AgentExtensionComponent;
import com.chtholly.agent.context.ContextContribution;
import com.chtholly.agent.context.ContextContributor;
import com.chtholly.agent.context.ContextOrder;
import com.chtholly.agent.context.ContextRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** Renders optional knowledge-graph associations into the prompt. */
@Slf4j
@Component
@AgentExtensionComponent
@ConditionalOnProperty(prefix = "agent.extensions.graph", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GraphContextContributor implements ContextContributor {

    private final KnowledgeGraphService knowledgeGraphService;

    public GraphContextContributor(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    @Override
    public String name() {
        return "graph";
    }

    @Override
    public int order() {
        return ContextOrder.GRAPH;
    }

    @Override
    public ContextContribution contribute(ContextRequest request) {
        String question = request.userQuestion();
        if (question == null || question.isBlank()) {
            return ContextContribution.empty(name(), order(), false);
        }
        try {
            List<String> graphContext = knowledgeGraphService.contextForQuestion(question, 5);
            if (graphContext == null || graphContext.isEmpty()) {
                return ContextContribution.empty(name(), order(), false);
            }
            StringBuilder prompt = new StringBuilder("## 话题关联\n\n");
            for (String line : graphContext) {
                if (line != null && !line.isBlank()) {
                    prompt.append("- ").append(line.trim()).append('\n');
                }
            }
            return new ContextContribution(name(), order(), prompt.toString(), false);
        } catch (RuntimeException e) {
            log.warn("Knowledge graph context failed", e);
            return ContextContribution.empty(name(), order(), true);
        }
    }
}
