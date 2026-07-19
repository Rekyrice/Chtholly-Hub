package com.chtholly.agent.skill;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic, whitelist-bound primary Skill selector. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class SkillSelector {

    public SkillSelection select(List<SkillDefinition> candidates, SkillExecutionContext context) {
        List<SkillDefinition> allowedCandidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (!context.taskType().isBlank()) {
            List<SkillDefinition> explicit = allowedCandidates.stream()
                    .filter(definition -> definition.id().equals(context.taskType()))
                    .toList();
            if (explicit.size() != 1) {
                return SkillSelection.terminal(
                        Status.CLARIFICATION_REQUIRED, "unknown_or_ambiguous_task_type");
            }
            return selected(explicit.getFirst(), "explicit_task_type", context);
        }

        String question = context.question().toLowerCase(Locale.ROOT);
        List<SkillDefinition> matched = new ArrayList<>();
        for (SkillDefinition definition : allowedCandidates) {
            if (matches(definition.id(), question)) {
                matched.add(definition);
            }
        }
        if (matched.size() > 1) {
            return SkillSelection.terminal(Status.CLARIFICATION_REQUIRED, "rule_conflict");
        }
        if (matched.isEmpty()) {
            return SkillSelection.terminal(Status.NO_MATCH, "no_deterministic_match");
        }
        return selected(matched.getFirst(), "deterministic_rule", context);
    }

    private SkillSelection selected(
            SkillDefinition definition,
            String reason,
            SkillExecutionContext context) {
        if (!"READ_ONLY".equals(definition.riskLevel())) {
            return SkillSelection.terminal(
                    Status.CLARIFICATION_REQUIRED, "controlled_write_requires_preview_api");
        }
        if (definition.requiredContext().contains("PAGE") && context.pageContext().isBlank()) {
            return SkillSelection.terminal(Status.CLARIFICATION_REQUIRED, "required_page_context_missing");
        }
        Set<String> allowedTools = new LinkedHashSet<>(definition.allowedTools());
        allowedTools.retainAll(context.permittedToolNames());
        allowedTools.retainAll(context.enabledToolNames());
        return new SkillSelection(
                Status.SELECTED, definition, reason, 1.0, Set.copyOf(allowedTools));
    }

    private boolean matches(String skillId, String question) {
        return switch (skillId) {
            case "page-explain" -> containsAny(
                    question, "解释", "是什么", "这个页面", "这篇文章", "讲了什么");
            case "evidence-outline" -> containsAny(question, "大纲", "提纲", "文章结构", "写作结构");
            case "draft-fact-check" -> containsAny(
                    question, "事实核查", "核查", "查证", "是否准确", "草稿事实");
            default -> false;
        };
    }

    private boolean containsAny(String input, String... terms) {
        for (String term : terms) {
            if (input.contains(term)) {
                return true;
            }
        }
        return false;
    }

    public enum Status {
        SELECTED,
        CLARIFICATION_REQUIRED,
        NO_MATCH
    }

    public record SkillSelection(
            Status status,
            SkillDefinition definition,
            String reason,
            double confidence,
            Set<String> allowedTools) {

        public SkillSelection {
            allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        }

        private static SkillSelection terminal(Status status, String reason) {
            return new SkillSelection(status, null, reason, 0.0, Set.of());
        }
    }
}
