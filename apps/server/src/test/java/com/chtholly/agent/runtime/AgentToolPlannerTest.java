package com.chtholly.agent.runtime;

import com.chtholly.agent.skill.SkillDefinition;
import com.chtholly.agent.skill.SkillSelector;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolPlannerTest {

    private static final List<String> ALL_TOOLS = List.of(
            "article_rag",
            "fulltext_search",
            "post_read",
            "bangumi_search",
            "bangumi_characters",
            "bangumi_person_works",
            "web_search",
            "web_fetch");

    private final AgentToolPlanner planner = new AgentToolPlanner();

    @Test
    void selectedEvidenceSkillDoesNotRepeatSiteRetrievalInTheAgentLoop() {
        AgentToolPlanner.ToolPlan plan = planner.plan(
                selected("page-explain", Set.copyOf(ALL_TOOLS)),
                "只依据当前文章总结三个观点",
                ALL_TOOLS);

        assertThat(plan.effectiveTools()).isEmpty();
        assertThat(plan.reason()).isEqualTo("selected_skill_evidence_only");
    }

    @Test
    void siteOnlyQuestionNeverExposesExternalWebTools() {
        AgentToolPlanner.ToolPlan plan = planner.plan(
                selected("evidence-outline", Set.copyOf(ALL_TOOLS)),
                "只依据站内文章整理《迷宫饭》的四节证据大纲",
                ALL_TOOLS);

        assertThat(plan.effectiveTools()).isEmpty();
        assertThat(plan.reason()).isEqualTo("selected_skill_evidence_only");
    }

    @Test
    void explicitUrlExposesOnlyWebFetch() {
        AgentToolPlanner.ToolPlan plan = planner.plan(
                selected("page-explain", Set.copyOf(ALL_TOOLS)),
                "请阅读 https://example.com/review 并总结作者观点",
                ALL_TOOLS);

        assertThat(plan.effectiveTools()).containsExactly("web_fetch");
        assertThat(plan.reason()).isEqualTo("selected_skill_web_fetch");
    }

    @Test
    void explicitExternalResearchIntentExposesSearchThenFetch() {
        AgentToolPlanner.ToolPlan plan = planner.plan(
                selected("draft-fact-check", Set.copyOf(ALL_TOOLS)),
                "联网调研近期官网资料并给出来源链接",
                ALL_TOOLS);

        assertThat(plan.effectiveTools()).containsExactly("web_search", "web_fetch");
        assertThat(plan.reason()).isEqualTo("selected_skill_web_research");
    }

    @Test
    void evidenceOnlyPlanPreservesOtherPermissionBoundToolsInStableOrder() {
        List<String> available = List.of(
                "article_rag",
                "site_post_detail",
                "fulltext_search",
                "site_author_posts",
                "bangumi_search");
        AgentToolPlanner.ToolPlan plan = planner.plan(
                selected("page-explain", Set.copyOf(available)),
                "只依据当前文章解释三个观点",
                available);

        assertThat(plan.effectiveTools())
                .containsExactly("site_post_detail", "site_author_posts");
        assertThat(plan.reason()).isEqualTo("selected_skill_evidence_only");
    }

    @Test
    void bangumiSubjectFactsExposeOnlySubjectSearch() {
        AgentToolPlanner.ToolPlan plan = planner.plan(
                selected("draft-fact-check", Set.copyOf(ALL_TOOLS)),
                "核查《迷宫饭》的评分、集数和放送时间",
                ALL_TOOLS);

        assertThat(plan.effectiveTools()).containsExactly("bangumi_search");
        assertThat(plan.reason()).isEqualTo("selected_skill_bangumi_subject");
    }

    @Test
    void characterQuestionsExposeSubjectSearchAndCharacterLookupOnly() {
        AgentToolPlanner.ToolPlan plan = planner.plan(
                selected("page-explain", Set.copyOf(ALL_TOOLS)),
                "《迷宫饭》的主要角色和声优有哪些？",
                ALL_TOOLS);

        assertThat(plan.effectiveTools())
                .containsExactly("bangumi_search", "bangumi_characters");
        assertThat(plan.reason()).isEqualTo("selected_skill_bangumi_characters");
    }

    @Test
    void authorWorkQuestionsExposeOnlyPersonWorksLookup() {
        AgentToolPlanner.ToolPlan plan = planner.plan(
                selected("evidence-outline", Set.copyOf(ALL_TOOLS)),
                "整理《迷宫饭》作者还画过哪些作品的大纲",
                ALL_TOOLS);

        assertThat(plan.effectiveTools()).containsExactly("bangumi_person_works");
        assertThat(plan.reason()).isEqualTo("selected_skill_bangumi_person_works");
    }

    @Test
    void currentArticleAuthorViewDoesNotLookLikeAWorkCreatorLookup() {
        AgentToolPlanner.ToolPlan plan = planner.plan(
                selected("page-explain", Set.copyOf(ALL_TOOLS)),
                "只依据当前文章总结作者的三个主要观点",
                ALL_TOOLS);

        assertThat(plan.effectiveTools()).isEmpty();
        assertThat(plan.reason()).isEqualTo("selected_skill_evidence_only");
    }

    @Test
    void selectedSkillPlanNeverExpandsItsPermissionIntersection() {
        AgentToolPlanner.ToolPlan plan = planner.plan(
                selected("page-explain", Set.of("bangumi_search")),
                "《迷宫饭》的主要角色有哪些？",
                ALL_TOOLS);

        assertThat(plan.effectiveTools()).containsExactly("bangumi_search");
    }

    @Test
    void genericChatKeepsTheExistingCoreToolSetAndOrderWithoutImplicitWebAccess() {
        List<String> available = List.of(
                "fulltext_search",
                "web_search",
                "article_rag",
                "post_read",
                "web_fetch",
                "bangumi_search",
                "bangumi_characters",
                "bangumi_person_works");

        SkillSelector.SkillSelection noMatch = new SkillSelector.SkillSelection(
                SkillSelector.Status.NO_MATCH, null, "no_deterministic_match", 0.0, Set.of());
        AgentToolPlanner.ToolPlan plan = planner.plan(noMatch, "随便聊聊", available);

        assertThat(plan.effectiveTools()).containsExactly(
                "fulltext_search",
                "article_rag",
                "post_read",
                "bangumi_search",
                "bangumi_characters",
                "bangumi_person_works");
        assertThat(plan.reason()).isEqualTo("generic_chat_core_tools");
    }

    @Test
    void genericExternalResearchUsesOnlyWebResearchTools() {
        SkillSelector.SkillSelection noMatch = new SkillSelector.SkillSelection(
                SkillSelector.Status.NO_MATCH, null, "no_deterministic_match", 0.0, Set.of());

        AgentToolPlanner.ToolPlan plan = planner.plan(
                noMatch,
                "去网上搜索最近的制作访谈，再阅读原文",
                ALL_TOOLS);

        assertThat(plan.effectiveTools()).containsExactly("web_search", "web_fetch");
        assertThat(plan.reason()).isEqualTo("generic_chat_web_research");
    }

    @Test
    void explicitNoNetworkConstraintWinsOverPositiveNetworkKeyword() {
        SkillSelector.SkillSelection noMatch = new SkillSelector.SkillSelection(
                SkillSelector.Status.NO_MATCH, null, "no_deterministic_match", 0.0, Set.of());

        AgentToolPlanner.ToolPlan plan = planner.plan(
                noMatch,
                "不要联网，也不要搜索网页，只用站内内容回答",
                ALL_TOOLS);

        assertThat(plan.effectiveTools()).containsExactly("article_rag", "fulltext_search", "post_read");
        assertThat(plan.reason()).isEqualTo("generic_chat_site_only");
    }

    @Test
    void colloquialNoNetworkConstraintWinsForSelectedSkill() {
        AgentToolPlanner.ToolPlan plan = planner.plan(
                selected("page-explain", Set.copyOf(ALL_TOOLS)),
                "别联网，也别查网页，只根据现有内容解释",
                ALL_TOOLS);

        assertThat(plan.effectiveTools()).doesNotContain("web_search", "web_fetch");
        assertThat(plan.reason()).isEqualTo("selected_skill_evidence_only");
    }

    @Test
    void conciseNoNetworkConstraintWinsForGenericChat() {
        SkillSelector.SkillSelection noMatch = new SkillSelector.SkillSelection(
                SkillSelector.Status.NO_MATCH, null, "no_deterministic_match", 0.0, Set.of());

        AgentToolPlanner.ToolPlan plan = planner.plan(
                noMatch,
                "不联网查这个问题，只用站内内容回答",
                ALL_TOOLS);

        assertThat(plan.effectiveTools()).containsExactly("article_rag", "fulltext_search", "post_read");
        assertThat(plan.reason()).isEqualTo("generic_chat_site_only");
    }

    @Test
    void ordinaryMentionOfAWebPageDoesNotImplicitlyOpenNetworkTools() {
        SkillSelector.SkillSelection noMatch = new SkillSelector.SkillSelection(
                SkillSelector.Status.NO_MATCH, null, "no_deterministic_match", 0.0, Set.of());

        AgentToolPlanner.ToolPlan plan = planner.plan(
                noMatch,
                "这个网页的文字排版看起来有点挤",
                ALL_TOOLS);

        assertThat(plan.effectiveTools()).doesNotContain("web_search", "web_fetch");
        assertThat(plan.reason()).isEqualTo("generic_chat_core_tools");
    }

    @Test
    void selectedSkillWebRoutingDoesNotExpandItsAllowlist() {
        AgentToolPlanner.ToolPlan plan = planner.plan(
                selected("page-explain", Set.of("web_fetch")),
                "联网搜索近期资料",
                ALL_TOOLS);

        assertThat(plan.effectiveTools()).containsExactly("web_fetch");
    }

    private SkillSelector.SkillSelection selected(String id, Set<String> allowedTools) {
        SkillDefinition definition = new SkillDefinition(
                id,
                "v1",
                true,
                "test",
                List.of(),
                List.of("QUESTION"),
                ALL_TOOLS,
                "test",
                Map.of(),
                Map.of("type", "TEST"),
                List.of(),
                "READ_ONLY",
                "NONE",
                30_000,
                4,
                "test-v1");
        return new SkillSelector.SkillSelection(
                SkillSelector.Status.SELECTED,
                definition,
                "test",
                1.0,
                new LinkedHashSet<>(allowedTools));
    }
}
