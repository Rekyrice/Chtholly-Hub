package com.chtholly.agent.skill;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillRegistryTest {

    private final SkillOutputValidator validator = new SkillOutputValidator();

    @Test
    void loadsExactlyThreeVersionedReadOnlySkillsFromClasspath() throws Exception {
        SkillRegistry registry = new SkillRegistry(
                List.of(new PathMatchingResourcePatternResolver().getResources(
                        "classpath*:agent/skills/*/v1.yml")),
                readOnlyTools(),
                validator,
                ignored -> true);

        assertThat(registry.enabled()).extracting(SkillDefinition::key)
                .containsExactly(
                        "draft-fact-check@v1",
                        "evidence-outline@v1",
                        "page-explain@v1");
        assertThat(registry.require("page-explain", "v1").allowedTools())
                .contains("article_rag", "fulltext_search")
                .doesNotContain("draft_write");
    }

    @Test
    void rejectsDuplicateVersionUnknownToolAndUnknownValidatorAtStartup() {
        String valid = definition("page-explain", "article_rag", "citation");
        assertThatThrownBy(() -> registry(valid, valid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate Skill page-explain@v1");
        assertThatThrownBy(() -> registry(definition("page-explain", "draft_write", "citation")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown Skill tool draft_write");
        assertThatThrownBy(() -> registry(definition("page-explain", "article_rag", "arbitrary")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown Skill validator arbitrary");
    }

    @Test
    void eachPreinstalledSkillCanBeDisabledIndependently() throws Exception {
        SkillRegistry registry = new SkillRegistry(
                List.of(new PathMatchingResourcePatternResolver().getResources(
                        "classpath*:agent/skills/*/v1.yml")),
                readOnlyTools(),
                validator,
                id -> !"evidence-outline".equals(id));

        assertThat(registry.enabled()).extracting(SkillDefinition::id)
                .containsExactly("draft-fact-check", "page-explain");
        assertThat(registry.require("evidence-outline", "v1")).isNotNull();
    }

    @Test
    void rejectsDefinitionsOutsideClosedSkillAndVersionSet() {
        assertThatThrownBy(() -> registry(definition("arbitrary-skill", "article_rag", "citation")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported Skill arbitrary-skill@v1");
        assertThatThrownBy(() -> registry(
                definition("page-explain", "article_rag", "citation")
                        .replace("version: v1", "version: v2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported Skill page-explain@v2");
    }

    private SkillRegistry registry(String... yaml) {
        List<ByteArrayResource> resources = Arrays.stream(yaml)
                .map(value -> new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8)))
                .toList();
        return new SkillRegistry(resources, Set.of("article_rag"), validator, ignored -> true);
    }

    private Set<String> readOnlyTools() {
        return Set.of("article_rag", "fulltext_search", "bangumi_search",
                "bangumi_characters", "bangumi_person_works");
    }

    private String definition(String id, String tool, String validatorId) {
        return """
                id: %s
                version: v1
                enabled: true
                description: test
                supportedIntents: [explain]
                requiredContext: [QUESTION]
                allowedTools: [%s]
                instructionTemplate: test instruction
                inputSchema: {question: string}
                outputSchema: {type: PAGE_EXPLAIN, requiresEvidence: true}
                validators: [%s]
                riskLevel: READ_ONLY
                approvalPolicy: NONE
                timeoutBudgetMs: 10000
                maxSteps: 3
                evaluationDatasetVersion: test-v1
                """.formatted(id, tool, validatorId);
    }
}
