package com.chtholly.agent.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SkillSelectorTest {

    private SkillRegistry registry;
    private SkillSelector selector;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SkillRegistry(
                List.of(new PathMatchingResourcePatternResolver().getResources(
                        "classpath*:agent/skills/*/v1.yml")),
                Set.of("article_rag", "post_read", "fulltext_search", "bangumi_search",
                        "bangumi_characters", "bangumi_person_works", "web_search", "web_fetch"),
                new SkillOutputValidator(),
                ignored -> true);
        selector = new SkillSelector();
    }

    @Test
    void explicitTaskTypeWinsAndCannotExpandToolPermissions() {
        SkillSelector.SkillSelection selection = selector.select(
                registry.enabled(),
                context("evidence-outline", "请给出文章大纲", "页面：文章详情",
                        Set.of("fulltext_search"),
                        Set.of("fulltext_search", "article_rag", "draft_write")));

        assertThat(selection.status()).isEqualTo(SkillSelector.Status.SELECTED);
        assertThat(selection.definition().id()).isEqualTo("evidence-outline");
        assertThat(selection.allowedTools()).containsExactly("fulltext_search");
        assertThat(selection.reason()).isEqualTo("explicit_task_type");
    }

    @Test
    void explicitConceptExplanationDoesNotRequirePageAndOverridesImplicitRules() {
        SkillSelector.SkillSelection conceptWithoutPage = selector.select(
                registry.enabled(), context(
                        "page-explain",
                        "请事实核查这份大纲并解释 Redis 的缓存一致性",
                        "",
                        Set.of(),
                        Set.of()));
        SkillSelector.SkillSelection withPage = selector.select(
                registry.enabled(), context("page-explain", "解释", "页面：文章详情", Set.of(), Set.of()));

        assertThat(conceptWithoutPage.status()).isEqualTo(SkillSelector.Status.SELECTED);
        assertThat(conceptWithoutPage.definition().id()).isEqualTo("page-explain");
        assertThat(conceptWithoutPage.reason()).isEqualTo("explicit_task_type");
        assertThat(withPage.status()).isEqualTo(SkillSelector.Status.SELECTED);
    }

    @Test
    void keywordFallbackRemainsCompatibleWithoutExplicitTaskType() {
        SkillSelector.SkillSelection selection = selector.select(
                registry.enabled(), context("", "请给我一份 Redis 缓存一致性大纲", "", Set.of(), Set.of()));

        assertThat(selection.status()).isEqualTo(SkillSelector.Status.SELECTED);
        assertThat(selection.definition().id()).isEqualTo("evidence-outline");
        assertThat(selection.reason()).isEqualTo("deterministic_rule");
    }

    @Test
    void currentArticleEvidenceSummarySelectsPageExplain() {
        SkillSelector.SkillSelection selection = selector.select(
                registry.enabled(),
                context(
                        "",
                        "只依据当前文章，总结作者的三个主要观点，并标出证据编号",
                        "source: post:dungeon-meshi",
                        Set.of("article_rag"),
                        Set.of("article_rag")));

        assertThat(selection.status()).isEqualTo(SkillSelector.Status.SELECTED);
        assertThat(selection.definition().id()).isEqualTo("page-explain");
    }

    @Test
    void implicitRulesUseFactCheckThenOutlineThenExplanationPriority() {
        SkillSelector.SkillSelection factCheck = selector.select(
                registry.enabled(),
                context("", "请验证真假，并把核查结果整理成证据大纲", "", Set.of(), Set.of()));
        SkillSelector.SkillSelection outline = selector.select(
                registry.enabled(),
                context("", "先总结材料，再给出章节安排和论证框架", "", Set.of(), Set.of()));
        SkillSelector.SkillSelection explanation = selector.select(
                registry.enabled(),
                context("", "概括本文的核心观点", "source: post:test", Set.of(), Set.of()));

        assertThat(factCheck.status()).isEqualTo(SkillSelector.Status.SELECTED);
        assertThat(factCheck.definition().id()).isEqualTo("draft-fact-check");
        assertThat(outline.status()).isEqualTo(SkillSelector.Status.SELECTED);
        assertThat(outline.definition().id()).isEqualTo("evidence-outline");
        assertThat(explanation.status()).isEqualTo(SkillSelector.Status.SELECTED);
        assertThat(explanation.definition().id()).isEqualTo("page-explain");
    }

    @Test
    void implicitRulesRecognizeNaturalChineseVariants() {
        assertThat(selectImplicit("请帮我求证这段资料是否属实").definition().id())
                .isEqualTo("draft-fact-check");
        assertThat(selectImplicit("给这个主题设计一份目录").definition().id())
                .isEqualTo("evidence-outline");
        assertThat(selectImplicit("梳理一下作者的三个主要观点").definition().id())
                .isEqualTo("page-explain");
    }

    @Test
    void standaloneWhatIsQuestionDoesNotImplicitlySelectPageExplain() {
        SkillSelector.SkillSelection selection = selectImplicit("《迷宫饭》的评分是什么");

        assertThat(selection.status()).isEqualTo(SkillSelector.Status.NO_MATCH);
        assertThat(selection.reason()).isEqualTo("no_deterministic_match");
    }

    @Test
    void controlledWriteSkillCannotEnterGeneralAgentToolLoop() {
        SkillDefinition draftEdit = new SkillDefinition(
                "draft-edit", "v1", true, "test", List.of("draft_edit"),
                List.of("QUESTION", "PAGE"), List.of(), "preview only",
                java.util.Map.of(), java.util.Map.of("type", "DRAFT_EDIT"), List.of(),
                "CONTROLLED_WRITE", "EXPLICIT_CONFIRMATION", 30_000, 3, "test-v1");

        SkillSelector.SkillSelection selection = selector.select(
                List.of(draftEdit),
                context("draft-edit", "修改草稿", "页面：草稿", Set.of(), Set.of()));

        assertThat(selection.status()).isEqualTo(SkillSelector.Status.CLARIFICATION_REQUIRED);
        assertThat(selection.reason()).isEqualTo("controlled_write_requires_preview_api");
    }

    private SkillExecutionContext context(String taskType,
                                          String question,
                                          String pageContext,
                                          Set<String> permitted,
                                          Set<String> enabled) {
        return new SkillExecutionContext(
                7L, "chat-1", taskType, question, pageContext, permitted, enabled);
    }

    private SkillSelector.SkillSelection selectImplicit(String question) {
        return selector.select(
                registry.enabled(),
                context("", question, "source: post:test", Set.of(), Set.of()));
    }
}
