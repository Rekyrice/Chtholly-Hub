package com.chtholly.agent.runtime;

import com.chtholly.agent.skill.SkillSelector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Produces the deterministic, permission-bounded tool set for one Agent turn. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentToolPlanner {

    private static final String BANGUMI_SEARCH = "bangumi_search";
    private static final String BANGUMI_CHARACTERS = "bangumi_characters";
    private static final String BANGUMI_PERSON_WORKS = "bangumi_person_works";
    private static final Set<String> PRELOOP_SITE_RETRIEVAL = Set.of(
            "article_rag",
            "fulltext_search");
    private static final Set<String> INTENT_ROUTED_BANGUMI_TOOLS = Set.of(
            BANGUMI_SEARCH,
            BANGUMI_CHARACTERS,
            BANGUMI_PERSON_WORKS);
    private static final Set<String> EVIDENCE_SKILLS = Set.of(
            "page-explain",
            "evidence-outline",
            "draft-fact-check");

    /**
     * Plans the tools visible to the model without executing any tool or retrieval.
     *
     * @param selection selected Skill, or {@code null} for generic chat
     * @param question current user question
     * @param availableToolNames tools enabled for this runtime
     * @return immutable plan with a stable reason and ordered effective tools
     */
    public ToolPlan plan(
            SkillSelector.SkillSelection selection,
            String question,
            Collection<String> availableToolNames) {
        List<String> available = distinct(availableToolNames);
        if (!isSelected(selection)) {
            return new ToolPlan("generic_chat_all_tools", available);
        }

        Set<String> permitted = new LinkedHashSet<>(selection.allowedTools());
        permitted.retainAll(available);
        String skillId = selection.definition().id();
        if (!EVIDENCE_SKILLS.contains(skillId)) {
            return new ToolPlan(
                    "selected_skill_allowlist",
                    available.stream().filter(permitted::contains).toList());
        }

        String normalizedQuestion = question == null
                ? ""
                : question.strip().toLowerCase(Locale.ROOT);
        if (hasPersonWorksIntent(normalizedQuestion)) {
            return selectedPlan(
                    "selected_skill_bangumi_person_works",
                    permitted,
                    BANGUMI_PERSON_WORKS);
        }
        if (hasCharacterIntent(normalizedQuestion)) {
            return selectedPlan(
                    "selected_skill_bangumi_characters",
                    permitted,
                    BANGUMI_SEARCH,
                    BANGUMI_CHARACTERS);
        }
        if (hasSubjectIntent(normalizedQuestion)) {
            return selectedPlan(
                    "selected_skill_bangumi_subject",
                    permitted,
                    BANGUMI_SEARCH);
        }

        // Evidence Skills already receive station evidence from ContextEngine before the loop;
        // Bangumi tools are exposed only when a matching external-data intent was detected above.
        List<String> effective = available.stream()
                .filter(permitted::contains)
                .filter(tool -> !PRELOOP_SITE_RETRIEVAL.contains(tool))
                .filter(tool -> !INTENT_ROUTED_BANGUMI_TOOLS.contains(tool))
                .toList();
        return new ToolPlan("selected_skill_evidence_only", effective);
    }

    private ToolPlan selectedPlan(String reason, Set<String> permitted, String... desiredTools) {
        List<String> effective = List.of(desiredTools).stream()
                .filter(permitted::contains)
                .toList();
        return new ToolPlan(reason, effective);
    }

    private boolean hasSubjectIntent(String question) {
        return containsAny(
                question,
                "评分", "分数", "排名", "集数", "话数", "放送", "播出", "开播", "季数", "哪一季",
                "bangumi 条目", "条目信息");
    }

    private boolean hasCharacterIntent(String question) {
        return containsAny(
                question,
                "角色", "人物", "主角", "配角", "登场", "声优", "配音", "cv");
    }

    private boolean hasPersonWorksIntent(String question) {
        return containsAny(
                question,
                "作者是谁", "作者还", "作者作品", "作者的其他", "漫画家", "原作者", "原作是谁",
                "还画过", "其他作品", "参与作品", "作品列表");
    }

    private boolean containsAny(String input, String... terms) {
        for (String term : terms) {
            if (input.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSelected(SkillSelector.SkillSelection selection) {
        return selection != null
                && selection.status() == SkillSelector.Status.SELECTED
                && selection.definition() != null;
    }

    private List<String> distinct(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                distinct.add(value);
            }
        }
        return List.copyOf(distinct);
    }

    /** Immutable result of one pure tool-planning decision. */
    public record ToolPlan(String reason, List<String> effectiveTools) {

        public ToolPlan {
            reason = reason == null ? "" : reason.strip();
            effectiveTools = effectiveTools == null ? List.of() : List.copyOf(effectiveTools);
        }
    }
}
