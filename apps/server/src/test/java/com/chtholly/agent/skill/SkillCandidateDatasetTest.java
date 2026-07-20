package com.chtholly.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes the unreviewed Skill candidates against the deterministic selector and tool boundary. */
class SkillCandidateDatasetTest {

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "article_rag", "fulltext_search", "bangumi_search",
            "bangumi_characters", "bangumi_person_works");
    private static final Set<String> FORBIDDEN_WRITE_TOOLS = Set.of("draft_write", "post_publish");

    private SkillRegistry registry;
    private SkillSelector selector;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SkillRegistry(
                List.of(new PathMatchingResourcePatternResolver().getResources(
                        "classpath*:agent/skills/*/v1.yml")),
                READ_ONLY_TOOLS,
                new SkillOutputValidator(),
                ignored -> true);
        selector = new SkillSelector();
    }

    @Test
    void allTwentySevenCandidatesPreserveReadOnlyRoutingAndPageContextBounds() throws Exception {
        List<JsonNode> candidates = readCandidates();
        assertThat(candidates).hasSize(27);

        for (JsonNode candidate : candidates) {
            assertThat(candidate.path("labelStatus").asText())
                    .isEqualTo("CANDIDATE_REQUIRES_OWNER_REVIEW");
            assertThat(candidate.path("acceptedSkillIds")).isEmpty();
            assertThat(candidate.path("humanLabels").isNull()).isTrue();
            assertThat(candidate.path("judgeLabels").isNull()).isTrue();
            assertThat(candidate.path("ruleLabels").path("schemaValid").asBoolean()).isTrue();
            assertThat(candidate.path("ruleLabels").path("readOnly").asBoolean()).isTrue();

            JsonNode input = candidate.path("input");
            assertThat(input.path("pageContext").asText()).isNotBlank();
            String proposedSkillId = candidate.path("proposedSkillId").asText();
            Set<String> forbiddenTools = strings(candidate.path("forbiddenTools"));
            Set<String> enabledTools = new LinkedHashSet<>(READ_ONLY_TOOLS);
            enabledTools.addAll(FORBIDDEN_WRITE_TOOLS);

            SkillSelector.SkillSelection explicit = selector.select(
                    registry.enabled(),
                    context(proposedSkillId, input, enabledTools));
            SkillSelector.SkillSelection inferred = selector.select(
                    registry.enabled(),
                    context("", input, enabledTools));
            SkillSelector.SkillSelection missingPage = selector.select(
                    registry.enabled(),
                    new SkillExecutionContext(7L, "candidate", proposedSkillId,
                            input.path("question").asText(), "", enabledTools, enabledTools));

            assertThat(explicit.status()).isEqualTo(SkillSelector.Status.SELECTED);
            assertThat(explicit.definition().id()).isEqualTo(proposedSkillId);
            assertThat(explicit.definition().riskLevel()).isEqualTo("READ_ONLY");
            assertThat(explicit.definition().approvalPolicy()).isEqualTo("NONE");
            assertThat(explicit.allowedTools()).containsAll(strings(candidate.path("requiredTools")));
            assertThat(explicit.allowedTools()).doesNotContainAnyElementsOf(forbiddenTools);
            assertThat(inferred.status()).isEqualTo(SkillSelector.Status.SELECTED);
            assertThat(inferred.definition().id()).isEqualTo(proposedSkillId);
            if (explicit.definition().requiredContext().contains("PAGE")) {
                assertThat(missingPage.status()).isEqualTo(SkillSelector.Status.CLARIFICATION_REQUIRED);
                assertThat(missingPage.reason()).isEqualTo("required_page_context_missing");
            } else {
                assertThat(missingPage.status()).isEqualTo(SkillSelector.Status.SELECTED);
            }
        }
    }

    private SkillExecutionContext context(String taskType, JsonNode input, Set<String> tools) {
        return new SkillExecutionContext(
                7L,
                "candidate",
                taskType,
                input.path("question").asText(),
                input.path("pageContext").asText(),
                tools,
                tools);
    }

    private List<JsonNode> readCandidates() throws Exception {
        Path dataset = findRepositoryRoot().resolve(
                "benchmarks/datasets/agent-evaluation/skills.jsonl");
        ObjectMapper objectMapper = new ObjectMapper();
        try (Stream<String> lines = Files.lines(dataset, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank())
                    .map(line -> readJson(objectMapper, line))
                    .toList();
        }
    }

    private Path findRepositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(
                    "benchmarks/datasets/agent-evaluation/skills.jsonl"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate the Skill candidate dataset");
    }

    private JsonNode readJson(ObjectMapper objectMapper, String line) {
        try {
            return objectMapper.readTree(line);
        } catch (Exception exception) {
            throw new IllegalStateException("Skill candidate is not valid JSON", exception);
        }
    }

    private Set<String> strings(JsonNode values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return Set.copyOf(result);
    }
}
