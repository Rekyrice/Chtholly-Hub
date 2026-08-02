package com.chtholly.agent.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRequestPlannerTest {

    private SkillRegistry registry;
    private SkillRequestPlanner planner;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SkillRegistry(
                List.of(new PathMatchingResourcePatternResolver().getResources(
                        "classpath*:agent/skills/*/v1.yml")),
                Set.of("article_rag", "fulltext_search", "bangumi_search",
                        "bangumi_characters", "bangumi_person_works", "web_search", "web_fetch"),
                new SkillOutputValidator(),
                ignored -> true);
        planner = new SkillRequestPlanner();
    }

    @Test
    void outlineWithoutTopicNeedsClarificationBeforeRetrieval() {
        assertClarification(
                planner.plan(
                        registry.require("evidence-outline", "v1"),
                        "根据站内资料生成一份文章大纲",
                        ""),
                "outline_topic_missing");
        assertClarification(
                planner.plan(
                        registry.require("evidence-outline", "v1"),
                        "给我列一个技术分享提纲",
                        ""),
                "outline_topic_missing");
    }

    @Test
    void generalOutlineUsesOptionalEvidenceAndExtractsTopic() {
        SkillRequestPlanner.SkillRequestPlan plan = planner.plan(
                registry.require("evidence-outline", "v1"),
                "给我列一个关于 Redis 缓存一致性的技术分享提纲",
                "");

        assertThat(plan.status()).isEqualTo(SkillRequestPlanner.PlanStatus.READY);
        assertThat(plan.evidencePolicy()).isEqualTo(EvidencePolicy.OPTIONAL);
        assertThat(plan.retrievalQuery()).isEqualTo("Redis 缓存一致性");
    }

    @Test
    void groundedOutlineRequiresEvidenceAndUsesTheSameCleanTopic() {
        SkillRequestPlanner.SkillRequestPlan plan = planner.plan(
                registry.require("evidence-outline", "v1"),
                "根据站内资料，生成一份关于 Redis 缓存一致性的文章大纲。",
                "");

        assertThat(plan.status()).isEqualTo(SkillRequestPlanner.PlanStatus.READY);
        assertThat(plan.evidencePolicy()).isEqualTo(EvidencePolicy.REQUIRED);
        assertThat(plan.retrievalQuery()).isEqualTo("Redis 缓存一致性");
    }

    @Test
    void outlineBasedOnSiteArticlesRequiresEvidence() {
        SkillRequestPlanner.SkillRequestPlan plan = planner.plan(
                registry.require("evidence-outline", "v1"),
                "基于站内文章，为“京都动画如何表现人物关系”生成一个至少四节的证据大纲",
                "");

        assertThat(plan.evidencePolicy()).isEqualTo(EvidencePolicy.REQUIRED);
        assertThat(plan.retrievalQuery()).isEqualTo("京都动画如何表现人物关系");
    }

    @Test
    void pageExplainAcceptsPostContextOrExplicitConcept() {
        SkillRequestPlanner.SkillRequestPlan currentPost = planner.plan(
                registry.require("page-explain", "v1"),
                "解释这篇文章",
                "页面：/agent\n标题：时间的重量\n来源：post:frieren-review\npostSlug：frieren-review");
        SkillRequestPlanner.SkillRequestPlan concept = planner.plan(
                registry.require("page-explain", "v1"),
                "解释一下 Redis 持久化",
                "");

        assertThat(currentPost.status()).isEqualTo(SkillRequestPlanner.PlanStatus.READY);
        assertThat(currentPost.evidencePolicy()).isEqualTo(EvidencePolicy.REQUIRED);
        assertThat(currentPost.retrievalQuery()).isEqualTo("时间的重量 frieren-review");
        assertThat(concept.status()).isEqualTo(SkillRequestPlanner.PlanStatus.READY);
        assertThat(concept.evidencePolicy()).isEqualTo(EvidencePolicy.OPTIONAL);
        assertThat(concept.retrievalQuery()).isEqualTo("Redis 持久化");
    }

    @Test
    void currentPostSummaryUsesTheRequestedAnalysisAsRetrievalQuery() {
        SkillRequestPlanner.SkillRequestPlan plan = planner.plan(
                registry.require("page-explain", "v1"),
                "只依据当前文章，总结作者的三个主要观点，并标出证据编号",
                "页面：/agent\n标题：吃掉红龙这件事\n来源：post:dungeon-meshi"
                        + "\npostSlug：dungeon-meshi\npostId：42");

        assertThat(plan.evidencePolicy()).isEqualTo(EvidencePolicy.REQUIRED);
        assertThat(plan.retrievalQuery())
                .isEqualTo("总结作者的三个主要观点，并标出证据编号");
    }

    @Test
    void genericAgentShellIsNotMistakenForGroundedPageContent() {
        String agentShell = "页面：/agent\n标题：珂朵莉 - Chtholly Hub";
        SkillRequestPlanner.SkillRequestPlan concept = planner.plan(
                registry.require("page-explain", "v1"),
                "解释一下 Redis 持久化",
                agentShell);
        SkillRequestPlanner.SkillRequestPlan missingTarget = planner.plan(
                registry.require("page-explain", "v1"),
                "解释这个页面",
                agentShell);

        assertThat(concept.status()).isEqualTo(SkillRequestPlanner.PlanStatus.READY);
        assertThat(concept.evidencePolicy()).isEqualTo(EvidencePolicy.OPTIONAL);
        assertThat(concept.retrievalQuery()).isEqualTo("Redis 持久化");
        assertClarification(missingTarget, "explain_target_missing");
    }

    @Test
    void pageExplainAndFactCheckValidateTheirRequiredObjects() {
        assertClarification(
                planner.plan(registry.require("page-explain", "v1"), "解释一下", ""),
                "explain_target_missing");
        assertClarification(
                planner.plan(registry.require("draft-fact-check", "v1"), "帮我做事实核查", ""),
                "fact_check_draft_missing");

        SkillRequestPlanner.SkillRequestPlan factCheck = planner.plan(
                registry.require("draft-fact-check", "v1"),
                "帮我核查：Redis 的所有命令都是原子操作。",
                "");
        assertThat(factCheck.status()).isEqualTo(SkillRequestPlanner.PlanStatus.READY);
        assertThat(factCheck.evidencePolicy()).isEqualTo(EvidencePolicy.REQUIRED);
        assertThat(factCheck.retrievalQuery()).isEqualTo("Redis 的所有命令都是原子操作");
    }

    private void assertClarification(
            SkillRequestPlanner.SkillRequestPlan plan,
            String expectedReason) {
        assertThat(plan.status()).isEqualTo(SkillRequestPlanner.PlanStatus.NEEDS_CLARIFICATION);
        assertThat(plan.reason()).isEqualTo(expectedReason);
        assertThat(plan.evidencePolicy()).isEqualTo(EvidencePolicy.NOT_NEEDED);
        assertThat(plan.retrievalQuery()).isBlank();
    }
}
