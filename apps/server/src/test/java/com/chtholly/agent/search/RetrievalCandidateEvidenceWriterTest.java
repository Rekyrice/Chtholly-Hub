package com.chtholly.agent.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalCandidateEvidenceWriterTest {

    private Path runDirectory;

    @AfterEach
    void cleanup() throws Exception {
        for (String name : List.of(
                "retrieval.candidate.evidence.run-id",
                "retrieval.candidate.evidence.repo-root",
                "retrieval.candidate.evidence.subject-commit",
                "retrieval.candidate.evidence.execution-commit",
                "retrieval.candidate.evidence.harness-commit",
                "retrieval.candidate.evidence.dataset-commit")) {
            System.clearProperty(name);
        }
        if (runDirectory != null && Files.isDirectory(runDirectory)) {
            try (var paths = Files.walk(runDirectory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.delete(path); } catch (Exception exception) {
                        throw new IllegalStateException("Cannot clean writer test output", exception);
                    }
                });
            }
        }
    }

    @Test
    void writesNonFormalFourMetricContractWithIndependentCommitIdentity() throws Exception {
        Path repoRoot = repositoryRoot();
        String runId = "retrieval-writer-" + UUID.randomUUID().toString().substring(0, 8);
        runDirectory = repoRoot.resolve(".benchmark-results").resolve(runId);
        setProperties(repoRoot, runId);

        List<RetrievalCandidateEvidenceWriter.CandidateObservation> observations = List.of(
                observation(List.of("retrieval-1"), true, List.of("post:1"), List.of("post:1")),
                observation(List.of("retrieval-2"), false, List.of(), List.of()));
        Map<String, RetrievalCandidateMetrics.Result> results = new LinkedHashMap<>();
        for (String mode : List.of("keyword-only", "vector-only", "three-way-document-rrf")) {
            results.put(mode, RetrievalCandidateMetrics.evaluate(
                    "CANDIDATE_REQUIRES_OWNER_REVIEW",
                    List.of(
                            metric(Set.of("post:1"), true, List.of("post:1"), false),
                            metric(Set.of(), false, List.of(), true))));
        }

        new RetrievalCandidateEvidenceWriter(new ObjectMapper()).write(
                java.time.Instant.parse("2026-07-19T00:00:00Z"), 2, 0,
                observations, results, "mysql:8.0", "elasticsearch:8.11.0");

        JsonNode manifest = new ObjectMapper().readTree(runDirectory.resolve("manifest.json").toFile());
        assertThat(manifest.path("labelStatus").asText()).isEqualTo("CANDIDATE_REQUIRES_OWNER_REVIEW");
        assertThat(manifest.path("reviewStatus").asText()).isEqualTo("COLLECTED_UNREVIEWED");
        assertThat(manifest.path("evidenceStatus").asText()).isEqualTo("CANDIDATE_DIAGNOSTIC_ONLY");
        assertThat(manifest.path("executionCommit").asText()).isEqualTo("b".repeat(40));
        assertThat(manifest.path("subjectCommit").asText()).isEqualTo("a".repeat(40));
        assertThat(manifest.path("harnessCommit").asText()).isEqualTo("c".repeat(40));
        assertThat(manifest.path("datasetCommit").asText()).isEqualTo("d".repeat(40));
        assertThat(manifest.path("formalGold").asBoolean()).isFalse();
        assertThat(manifest.path("semanticQualityEvidence").asBoolean()).isFalse();

        JsonNode summary = new ObjectMapper().readTree(runDirectory.resolve("summary.json").toFile());
        assertThat(summary.path("metrics").size()).isEqualTo(3);
        summary.path("metrics").forEach(metrics -> {
            assertThat(metrics.size()).isEqualTo(4);
            assertThat(metrics.has("recallAt5")).isTrue();
            assertThat(metrics.has("mrr")).isTrue();
            assertThat(metrics.path("citationValidityRate").isNull()).isTrue();
            assertThat(metrics.has("noAnswerAccuracy")).isTrue();
        });
        assertThat(Files.readString(runDirectory.resolve("checksums.sha256")))
                .contains("manifest.json", "raw/observations.json", "summary.json");
    }

    private RetrievalCandidateEvidenceWriter.CandidateObservation observation(
            List<String> sampleIds, boolean answerExists, List<String> relevant, List<String> ranked) {
        var route = new RetrievalCandidateEvidenceWriter.RouteObservation(
                ranked, ranked.isEmpty(), Map.of("route", "SUCCESS_RESULTS"));
        return new RetrievalCandidateEvidenceWriter.CandidateObservation(
                sampleIds, answerExists, relevant, route, route, route);
    }

    private RetrievalCandidateMetrics.Observation metric(
            Set<String> relevant, boolean answerExists, List<String> ranked, boolean noAnswer) {
        return new RetrievalCandidateMetrics.Observation(
                relevant, answerExists, ranked, List.of(), Set.of(), noAnswer);
    }

    private void setProperties(Path repoRoot, String runId) {
        System.setProperty("retrieval.candidate.evidence.run-id", runId);
        System.setProperty("retrieval.candidate.evidence.repo-root", repoRoot.toString());
        System.setProperty("retrieval.candidate.evidence.subject-commit", "a".repeat(40));
        System.setProperty("retrieval.candidate.evidence.execution-commit", "b".repeat(40));
        System.setProperty("retrieval.candidate.evidence.harness-commit", "c".repeat(40));
        System.setProperty("retrieval.candidate.evidence.dataset-commit", "d".repeat(40));
    }

    private Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null && !Files.isRegularFile(candidate.resolve(".gitignore"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) { throw new IllegalStateException("Cannot locate repository root"); }
        return candidate;
    }
}
