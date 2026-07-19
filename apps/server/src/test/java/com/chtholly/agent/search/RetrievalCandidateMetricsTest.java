package com.chtholly.agent.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalCandidateMetricsTest {

    @Test
    void candidateDatasetKeepsOwnerReviewBoundaryAndRequiredCoverage() throws Exception {
        List<JsonNode> candidates = readCandidates();

        assertThat(candidates).hasSize(45);
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.path("labelStatus").asText())
                    .isEqualTo("CANDIDATE_REQUIRES_OWNER_REVIEW");
            assertThat(candidate.path("answerExists").isNull()).isTrue();
            assertThat(candidate.path("humanLabels").isNull()).isTrue();
            assertThat(candidate.path("judgeLabels").isNull()).isTrue();
        });
        assertThat(candidates.stream()
                .filter(candidate -> candidate.path("proposedAnswerExists").asBoolean())
                .count()).isEqualTo(37);
        assertThat(candidates.stream()
                .filter(candidate -> !candidate.path("proposedAnswerExists").asBoolean())
                .count()).isEqualTo(8);
        assertThat(candidates).extracting(candidate -> candidate.path("quotaBucket").asText())
                .contains("factual-entity", "semantic", "cross-document", "no-answer");
    }

    @Test
    void computesOnlyRequestedMetricsAndMarksCandidateResultsNonFormal() {
        List<RetrievalCandidateMetrics.Observation> observations = List.of(
                observation(Set.of("post:1"), true,
                        List.of("post:1", "post:x"),
                        List.of("post:1#fact-1"), Set.of("post:1#fact-1"), false),
                observation(Set.of("post:2"), true,
                        List.of("post:x", "post:y", "post:z", "post:w", "post:q", "post:2"),
                        List.of("post:fake#fact-1"), Set.of("post:2#fact-1"), false),
                observation(Set.of(), false,
                        List.of(), List.of(), Set.of(), true),
                observation(Set.of(), false,
                        List.of("post:unrelated"), List.of(), Set.of(), false));

        RetrievalCandidateMetrics.Result result = RetrievalCandidateMetrics.evaluate(
                "CANDIDATE_REQUIRES_OWNER_REVIEW", observations);

        assertThat(result.recallAt5()).isEqualTo(0.5);
        assertThat(result.mrr()).isEqualTo(0.583333);
        assertThat(result.citationValidityRate()).isEqualTo(0.5);
        assertThat(result.noAnswerAccuracy()).isEqualTo(0.75);
        assertThat(result.answerableCount()).isEqualTo(2);
        assertThat(result.noAnswerCount()).isEqualTo(2);
        assertThat(result.formalGold()).isFalse();
        assertThat(result.evidenceStatus()).isEqualTo("CANDIDATE_DIAGNOSTIC_ONLY");
    }

    @Test
    void reportsCitationMetricAsNotObservedWhenGenerationDidNotRun() {
        RetrievalCandidateMetrics.Result result = RetrievalCandidateMetrics.evaluate(
                "CANDIDATE_REQUIRES_OWNER_REVIEW",
                List.of(observation(Set.of("post:1"), true,
                        List.of("post:1"), List.of(), Set.of(), false)));

        assertThat(result.citationValidityRate()).isNull();
        assertThat(result.formalGold()).isFalse();
    }

    private RetrievalCandidateMetrics.Observation observation(
            Set<String> relevantDocumentIds,
            boolean answerExists,
            List<String> rankedDocumentIds,
            List<String> citations,
            Set<String> evidenceCitationIds,
            boolean noAnswer) {
        return new RetrievalCandidateMetrics.Observation(
                relevantDocumentIds,
                answerExists,
                rankedDocumentIds,
                citations,
                evidenceCitationIds,
                noAnswer);
    }

    private List<JsonNode> readCandidates() throws Exception {
        Path dataset = findRepositoryRoot().resolve(
                "benchmarks/datasets/agent-evaluation/retrieval.jsonl");
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
                    "benchmarks/datasets/agent-evaluation/retrieval.jsonl"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate the Retrieval candidate dataset");
    }

    private JsonNode readJson(ObjectMapper objectMapper, String line) {
        try {
            return objectMapper.readTree(line);
        } catch (Exception exception) {
            throw new IllegalStateException("Retrieval candidate is not valid JSON", exception);
        }
    }
}
