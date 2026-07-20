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
                Set.of("article_rag", "fulltext_search", "bangumi_search",
                        "bangumi_characters", "bangumi_person_works"),
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
    void pageContextParticipatesInSelectionAndConflictsRequireClarification() {
        SkillSelector.SkillSelection missingPage = selector.select(
                registry.enabled(), context("page-explain", "解释", "", Set.of(), Set.of()));
        SkillSelector.SkillSelection withPage = selector.select(
                registry.enabled(), context("page-explain", "解释", "页面：文章详情", Set.of(), Set.of()));
        SkillSelector.SkillSelection conflict = selector.select(
                registry.enabled(), context("", "请核查草稿并给我证据大纲", "", Set.of(), Set.of()));

        assertThat(missingPage.status()).isEqualTo(SkillSelector.Status.CLARIFICATION_REQUIRED);
        assertThat(missingPage.reason()).isEqualTo("required_page_context_missing");
        assertThat(withPage.status()).isEqualTo(SkillSelector.Status.SELECTED);
        assertThat(conflict.status()).isEqualTo(SkillSelector.Status.CLARIFICATION_REQUIRED);
        assertThat(conflict.reason()).isEqualTo("rule_conflict");
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
}
