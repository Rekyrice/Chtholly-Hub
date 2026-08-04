package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.context.AgentContextSnapshot;
import com.chtholly.agent.context.ContextEngine;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.skill.EvidencePolicy;
import com.chtholly.agent.skill.SkillDefinition;
import com.chtholly.agent.skill.SkillRequestPlanner;
import com.chtholly.agent.skill.SkillSelector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads conversation memory and builds the retrieval-backed context for one agent turn.
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentContextPreparationService {

    private final ContextEngine contextEngine;
    private final AgentBoundedCallExecutor boundedCallExecutor;

    /**
     * Creates the context preparation boundary.
     *
     * @param contextEngine retrieval and prompt context engine
     * @param boundedCallExecutor whole-turn bounded call executor
     */
    public AgentContextPreparationService(
            ContextEngine contextEngine,
            AgentBoundedCallExecutor boundedCallExecutor) {
        this.contextEngine = contextEngine;
        this.boundedCallExecutor = boundedCallExecutor;
    }

    /**
     * Formats conversation memory within the remaining turn budget.
     *
     * @param memory optional session memory
     * @param budget active turn budget
     * @return prompt-ready history, or an empty string when memory is absent
     */
    public String readHistory(AgentConversationMemory memory, AgentTurnBudget budget) {
        return memory == null
                ? ""
                : boundedCallExecutor.execute(memory::formatForPrompt, budget, "memory_read");
    }

    /**
     * Builds the context snapshot and binds a selected Skill to both model prompts.
     *
     * @param request immutable retrieval inputs
     * @return context snapshot ready for the reasoning loop
     */
    public AgentContextSnapshot buildSnapshot(Request request) {
        boolean selected = request.selection() != null
                && request.selection().status() == SkillSelector.Status.SELECTED;
        AgentContextSnapshot snapshot = boundedCallExecutor.execute(
                () -> selected
                        ? contextEngine.buildSnapshot(
                                request.userId(),
                                request.sessionId(),
                                request.pageContext(),
                                request.tools().values(),
                                request.historyBlock(),
                                request.question().trim(),
                                request.taskPlan().evidencePolicy(),
                                request.taskPlan().retrievalQuery())
                        : contextEngine.buildSnapshot(
                                request.userId(),
                                request.sessionId(),
                                request.pageContext(),
                                request.tools().values(),
                                request.historyBlock(),
                                request.question().trim(),
                                false),
                request.turnBudget(),
                "retrieval");
        if (!selected) {
            return snapshot;
        }
        return snapshot.withSystemPrompts(
                bindSkillPrompt(
                        snapshot.systemPrompt(),
                        request.selection(),
                        request.taskPlan().evidencePolicy(),
                        request.tools().keySet()),
                bindSkillPrompt(
                        snapshot.finalSystemPrompt(),
                        request.selection(),
                        request.taskPlan().evidencePolicy(),
                        request.tools().keySet()));
    }

    private String bindSkillPrompt(
            String system,
            SkillSelector.SkillSelection selection,
            EvidencePolicy evidencePolicy,
            Set<String> effectiveTools) {
        SkillDefinition definition = selection.definition();
        return system + "\n\n## 当前领域 Skill\n\n"
                + "skillId=" + definition.id() + "\n"
                + "skillVersion=" + definition.version() + "\n"
                + "outputType=" + definition.outputType() + "\n"
                + "evidencePolicy=" + evidencePolicy.name() + "\n"
                + "allowedTools=" + effectiveTools.stream().sorted()
                .collect(Collectors.joining(",")) + "\n\n"
                + definition.instructionTemplate();
    }

    /** Immutable inputs required to build retrieval context. */
    public record Request(
            String question,
            long userId,
            String sessionId,
            String pageContext,
            Map<String, AgentTool> tools,
            String historyBlock,
            SkillSelector.SkillSelection selection,
            SkillRequestPlanner.SkillRequestPlan taskPlan,
            AgentTurnBudget turnBudget) {
    }
}
