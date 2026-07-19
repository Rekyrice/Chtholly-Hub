package com.chtholly.agent.skill;

import com.chtholly.agent.evidence.Evidence;
import com.chtholly.agent.evidence.EvidenceSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SkillOutputValidatorTest {

    private SkillRegistry registry;
    private SkillOutputValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        validator = new SkillOutputValidator();
        registry = new SkillRegistry(
                List.of(new PathMatchingResourcePatternResolver().getResources(
                        "classpath*:agent/skills/*/v1.yml")),
                Set.of("article_rag", "fulltext_search", "bangumi_search",
                        "bangumi_characters", "bangumi_person_works"),
                validator,
                ignored -> true);
    }

    @Test
    void evidenceRequiredSkillWithNoEvidenceReturnsExplicitNoAnswer() {
        SkillOutputValidator.SkillValidationResult result = validator.validate(
                registry.require("page-explain", "v1"),
                "这是一个没有依据的事实。",
                EvidenceSet.empty());

        assertThat(result.status()).isEqualTo(SkillOutputValidator.Status.INSUFFICIENT_EVIDENCE);
        assertThat(result.output()).isEqualTo(EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER);
    }

    @Test
    void outlineAndFactCheckEnforceStructureAndCurrentEvidenceCitations() {
        EvidenceSet evidence = evidence();
        var invalidOutline = validator.validate(
                registry.require("evidence-outline", "v1"), "只有一行 [E1]", evidence);
        var invalidFact = validator.validate(
                registry.require("draft-fact-check", "v1"),
                "| 主张 | 判定 | 证据 |\n| A | SUPPORTED | 无 |", evidence);
        var validFact = validator.validate(
                registry.require("draft-fact-check", "v1"),
                "| 主张 | 判定 | 证据 |\n| A | SUPPORTED | [E1] |\n| B | INSUFFICIENT | 无 |",
                evidence);

        assertThat(invalidOutline.status()).isEqualTo(SkillOutputValidator.Status.SCHEMA_INVALID);
        assertThat(invalidFact.status()).isEqualTo(SkillOutputValidator.Status.CITATION_INVALID);
        assertThat(validFact.status()).isEqualTo(SkillOutputValidator.Status.VALID);
    }

    @Test
    void draftEditRejectsNoOpAndOversizedCandidate() {
        SkillDefinition definition = new SkillDefinition(
                "draft-edit", "v1", true, "edit", List.of("draft_edit"),
                List.of("DRAFT_CONTENT", "USER_REQUEST"), List.of(), "preview",
                java.util.Map.of(), java.util.Map.of("type", "MARKDOWN_DRAFT", "maxChars", 12),
                List.of("draft-content", "length"), "CONTROLLED_WRITE", "EXPLICIT_CONFIRMATION",
                30_000, 1, "draft-edit-v1");

        var noOp = validator.validateDraftContent(definition, "same", "same");
        var oversized = validator.validateDraftContent(definition, "old", "0123456789abc");
        var valid = validator.validateDraftContent(definition, "old", "new content");

        assertThat(noOp.status()).isEqualTo(SkillOutputValidator.Status.CONSTRAINT_INVALID);
        assertThat(noOp.errors()).containsExactly("no_content_change");
        assertThat(oversized.errors()).containsExactly("max_chars_exceeded");
        assertThat(valid.status()).isEqualTo(SkillOutputValidator.Status.VALID);
        assertThat(valid.output()).isEqualTo("new content");
    }

    private EvidenceSet evidence() {
        Evidence item = new Evidence(
                "ev-1", "POST", "post:1", "post:1", "chunk-1",
                "v1", "hash-1", "证据内容", 1, 0.9, Set.of("PUBLIC"), "E1");
        return EvidenceSet.of(List.of(item), Set.of("PUBLIC"));
    }
}
