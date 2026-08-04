package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.skill.SkillDefinition;
import com.chtholly.agent.skill.SkillExecutionContext;
import com.chtholly.agent.skill.SkillRegistry;
import com.chtholly.agent.skill.SkillRequestPlanner;
import com.chtholly.agent.skill.SkillSelector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Selects a Skill and narrows the tools and budget available to one agent turn.
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentTurnPlanningService {

    private final List<AgentTool> tools;
    private final AgentToolPlanner toolPlanner;
    private final SkillRegistry skillRegistry;
    private final SkillSelector skillSelector;
    private final SkillRequestPlanner skillRequestPlanner;

    /**
     * Creates the deterministic turn-planning boundary.
     *
     * @param tools available agent tools
     * @param toolPlanner deterministic tool narrowing policy
     * @param skillRegistry enabled Skill registry
     * @param skillSelector Skill selection policy
     * @param skillRequestPlanner selected-Skill request planner
     */
    public AgentTurnPlanningService(
            List<AgentTool> tools,
            AgentToolPlanner toolPlanner,
            SkillRegistry skillRegistry,
            SkillSelector skillSelector,
            SkillRequestPlanner skillRequestPlanner) {
        this.tools = tools;
        this.toolPlanner = toolPlanner;
        this.skillRegistry = skillRegistry;
        this.skillSelector = skillSelector;
        this.skillRequestPlanner = skillRequestPlanner;
    }

    /**
     * Produces an immutable plan or an explicit clarification boundary.
     *
     * @param request stable inputs required for planning
     * @return ready plan or clarification boundary
     */
    public Plan plan(Request request) {
        Map<String, AgentTool> toolMap = toolMap();
        request.turnBudget().check("skill_selection");
        SkillSelector.SkillSelection selection = selectSkill(request, toolMap.keySet());
        request.turnBudget().check("skill_selection");
        if (selection != null
                && selection.status() == SkillSelector.Status.CLARIFICATION_REQUIRED) {
            request.trace().markFailure(AgentExecutionTrace.FailureType.SKILL_NO_MATCH);
            request.trace().recordOutcomeReason(
                    AgentExecutionTrace.OutcomeReason.NEEDS_CLARIFICATION);
            return Plan.boundary(
                    request.turnBudget(),
                    new Boundary("", selection.reason()));
        }

        AgentToolPlanner.ToolPlan toolPlan = toolPlanner.plan(
                selection, request.question(), toolMap.keySet());
        toolMap.keySet().retainAll(toolPlan.effectiveTools());
        request.trace().recordToolPlan(toolPlan.reason(), toolMap.keySet());
        request.trace().recordTools(toolMap.keySet());

        boolean selected = isSelected(selection);
        SkillDefinition selectedSkill = selected ? selection.definition() : null;
        int maxSteps = request.maxSteps();
        if (selectedSkill != null) {
            maxSteps = Math.min(maxSteps, selectedSkill.maxSteps());
            request.trace().limitMaxSteps(maxSteps);
        }
        AgentTurnBudget effectiveBudget = selectedSkill == null
                ? request.turnBudget()
                : request.turnBudget().limitFromStart(
                        Duration.ofMillis(selectedSkill.timeoutBudgetMs()));
        request.trace().recordTurnBudget(effectiveBudget.totalBudget());

        effectiveBudget.check("skill_planning");
        SkillRequestPlanner.SkillRequestPlan taskPlan = selected
                ? skillRequestPlanner.plan(
                        selectedSkill, request.question(), request.pageContext())
                : null;
        effectiveBudget.check("skill_planning");
        if (taskPlan != null
                && taskPlan.status() == SkillRequestPlanner.PlanStatus.NEEDS_CLARIFICATION) {
            request.trace().recordSkillValidation("NEEDS_CLARIFICATION");
            request.trace().recordOutcomeReason(
                    AgentExecutionTrace.OutcomeReason.NEEDS_CLARIFICATION);
            return Plan.boundary(
                    effectiveBudget,
                    new Boundary(selectedSkill.id(), taskPlan.reason()));
        }
        return Plan.ready(
                toolMap,
                selection,
                selectedSkill,
                taskPlan,
                maxSteps,
                effectiveBudget);
    }

    private Map<String, AgentTool> toolMap() {
        Map<String, AgentTool> toolMap = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            toolMap.put(tool.name(), tool);
        }
        return toolMap;
    }

    private SkillSelector.SkillSelection selectSkill(Request request, Set<String> availableTools) {
        try {
            SkillSelector.SkillSelection selection = null;
            if (skillRegistry != null && skillSelector != null) {
                Set<String> toolNames = Set.copyOf(availableTools);
                selection = skillSelector.select(
                        skillRegistry.enabled(),
                        new SkillExecutionContext(
                                request.userId(),
                                request.sessionId(),
                                request.taskType(),
                                request.question(),
                                request.pageContext(),
                                toolNames,
                                toolNames));
            }
            String status = selection == null ? "DISABLED" : selection.status().name();
            SkillDefinition definition = selection == null ? null : selection.definition();
            request.trace().recordSkillSelection(
                    status,
                    definition == null ? null : definition.id(),
                    definition == null ? null : definition.version());
            return selection;
        } catch (RuntimeException exception) {
            request.trace().recordSkillSelection("ERROR", null, null);
            request.trace().markFailure(AgentExecutionTrace.FailureType.INTERNAL_ERROR);
            throw exception;
        }
    }

    private boolean isSelected(SkillSelector.SkillSelection selection) {
        return selection != null && selection.status() == SkillSelector.Status.SELECTED;
    }

    /** Immutable inputs needed to select a Skill and plan tools. */
    public record Request(
            String question,
            long userId,
            String sessionId,
            String pageContext,
            String taskType,
            int maxSteps,
            AgentExecutionTrace trace,
            AgentTurnBudget turnBudget) {
    }

    /** Stable clarification boundary produced during planning. */
    public record Boundary(String skillId, String detail) {
    }

    /** Immutable result of Skill selection and tool planning. */
    public record Plan(
            boolean ready,
            Map<String, AgentTool> tools,
            SkillSelector.SkillSelection selection,
            SkillDefinition selectedSkill,
            SkillRequestPlanner.SkillRequestPlan taskPlan,
            int maxSteps,
            AgentTurnBudget turnBudget,
            Boundary boundary) {

        private static Plan ready(
                Map<String, AgentTool> tools,
                SkillSelector.SkillSelection selection,
                SkillDefinition selectedSkill,
                SkillRequestPlanner.SkillRequestPlan taskPlan,
                int maxSteps,
                AgentTurnBudget turnBudget) {
            return new Plan(
                    true,
                    Collections.unmodifiableMap(new LinkedHashMap<>(tools)),
                    selection,
                    selectedSkill,
                    taskPlan,
                    maxSteps,
                    turnBudget,
                    null);
        }

        private static Plan boundary(AgentTurnBudget turnBudget, Boundary boundary) {
            return new Plan(
                    false,
                    Map.of(),
                    null,
                    null,
                    null,
                    0,
                    turnBudget,
                    boundary);
        }
    }
}
