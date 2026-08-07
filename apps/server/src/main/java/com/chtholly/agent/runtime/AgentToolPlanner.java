package com.chtholly.agent.runtime;

import com.chtholly.agent.skill.SkillSelector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Produces the deterministic, permission-bounded tool set for one Agent turn. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentToolPlanner {

    private static final String BANGUMI_SEARCH = "bangumi_search";
    private static final String BANGUMI_CHARACTERS = "bangumi_characters";
    private static final String BANGUMI_PERSON_WORKS = "bangumi_person_works";
    private static final String WEB_SEARCH = "web_search";
    private static final String WEB_FETCH = "web_fetch";
    private static final Pattern HTTP_URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Set<String> PRELOOP_SITE_RETRIEVAL = Set.of(
            "article_rag",
            "fulltext_search",
            "post_read");
    private static final Set<String> INTENT_ROUTED_BANGUMI_TOOLS = Set.of(
            BANGUMI_SEARCH,
            BANGUMI_CHARACTERS,
            BANGUMI_PERSON_WORKS);
    private static final Set<String> WEB_TOOLS = Set.of(WEB_SEARCH, WEB_FETCH);
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
        String normalizedQuestion = question == null
                ? ""
                : question.strip().toLowerCase(Locale.ROOT);
        boolean selected = isSelected(selection);
        Set<String> permitted = selected
                ? permitted(selection, available)
                : new LinkedHashSet<>(available);

        if (hasSiteOnlyConstraint(normalizedQuestion)) {
            if (!selected) {
                return new ToolPlan(
                        "generic_chat_site_only",
                        available.stream().filter(PRELOOP_SITE_RETRIEVAL::contains).toList());
            }
            return evidenceOnlyPlan(permitted, available);
        }

        if (hasHttpUrl(normalizedQuestion)) {
            boolean alsoSearch = hasExplicitSearchIntent(normalizedQuestion);
            return selectedPlan(
                    selected
                            ? alsoSearch ? "selected_skill_web_research" : "selected_skill_web_fetch"
                            : alsoSearch ? "generic_chat_web_research" : "generic_chat_web_fetch",
                    permitted,
                    alsoSearch ? new String[]{WEB_SEARCH, WEB_FETCH} : new String[]{WEB_FETCH});
        }

        if (hasWebResearchIntent(normalizedQuestion)) {
            return selectedPlan(
                    selected ? "selected_skill_web_research" : "generic_chat_web_research",
                    permitted,
                    WEB_SEARCH,
                    WEB_FETCH);
        }

        if (!selected) {
            return new ToolPlan(
                    "generic_chat_core_tools",
                    available.stream().filter(tool -> !WEB_TOOLS.contains(tool)).toList());
        }

        String skillId = selection.definition().id();
        if (!EVIDENCE_SKILLS.contains(skillId)) {
            return new ToolPlan(
                    "selected_skill_allowlist",
                    available.stream().filter(permitted::contains).toList());
        }
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

        return evidenceOnlyPlan(permitted, available);
    }

    private ToolPlan evidenceOnlyPlan(Set<String> permitted, List<String> available) {
        // Evidence Skills already receive station evidence from ContextEngine before the loop;
        // external tools are exposed only when a matching deterministic intent was detected above.
        List<String> effective = available.stream()
                .filter(permitted::contains)
                .filter(tool -> !PRELOOP_SITE_RETRIEVAL.contains(tool))
                .filter(tool -> !INTENT_ROUTED_BANGUMI_TOOLS.contains(tool))
                .filter(tool -> !WEB_TOOLS.contains(tool))
                .toList();
        return new ToolPlan("selected_skill_evidence_only", effective);
    }

    private Set<String> permitted(
            SkillSelector.SkillSelection selection,
            List<String> available) {
        Set<String> permitted = new LinkedHashSet<>(selection.allowedTools());
        permitted.retainAll(available);
        return permitted;
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

    private boolean hasSiteOnlyConstraint(String question) {
        return containsAny(
                question,
                "只依据当前文章",
                "仅依据当前文章",
                "只根据当前文章",
                "仅根据当前文章",
                "只依据这篇文章",
                "仅依据这篇文章",
                "只依据站内",
                "仅依据站内",
                "只根据站内",
                "站内资料范围内",
                "不要联网",
                "不用联网",
                "无需联网",
                "不需要联网",
                "禁止联网",
                "别联网",
                "不联网",
                "勿联网",
                "不能联网",
                "不允许联网",
                "不查网络",
                "别查网络",
                "别查网页",
                "不要搜索网络",
                "不要搜索网页",
                "别搜索网络",
                "别搜索网页",
                "不要上网",
                "不用上网",
                "无需上网",
                "别上网",
                "不上网");
    }

    private boolean hasHttpUrl(String question) {
        return HTTP_URL.matcher(question).find();
    }

    private boolean hasExplicitSearchIntent(String question) {
        return containsAny(
                question,
                "再搜索",
                "继续搜索",
                "联网搜索",
                "网上搜索",
                "搜索网络",
                "搜索网页");
    }

    private boolean hasWebResearchIntent(String question) {
        return containsAny(
                question,
                "联网",
                "网上",
                "网络上",
                "上网",
                "互联网",
                "搜索网络",
                "搜索网页",
                "查网页",
                "查最新",
                "查一下最新",
                "最新消息",
                "最新资料",
                "近期新闻",
                "新闻",
                "官网",
                "来源链接",
                "外部资料",
                "公开资料",
                "网络资料",
                "外部调研",
                "网络调研",
                "web search");
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
