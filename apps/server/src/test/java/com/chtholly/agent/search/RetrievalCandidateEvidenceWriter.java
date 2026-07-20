package com.chtholly.agent.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes one ignored, non-formal retrieval candidate diagnostic package. */
final class RetrievalCandidateEvidenceWriter {

    private static final String LABEL_STATUS = "CANDIDATE_REQUIRES_OWNER_REVIEW";
    private static final String REVIEW_STATUS = "COLLECTED_UNREVIEWED";
    private final ObjectMapper objectMapper;

    RetrievalCandidateEvidenceWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(Instant startedAt,
               int candidateRowCount,
               int deduplicatedQueryGroups,
               List<CandidateObservation> observations,
               Map<String, RetrievalCandidateMetrics.Result> results,
               String mysqlImage,
               String elasticsearchImage) throws Exception {
        String runId = requiredProperty("retrieval.candidate.evidence.run-id");
        if (!runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) {
            throw new IllegalArgumentException("Retrieval evidence run ID is invalid");
        }
        Path repoRoot = Path.of(requiredProperty("retrieval.candidate.evidence.repo-root")).toRealPath();
        Path resultsRoot = repoRoot.resolve(".benchmark-results");
        Files.createDirectories(resultsRoot);
        Path runDirectory = resultsRoot.toRealPath().resolve(runId);
        if (!runDirectory.startsWith(repoRoot) || Files.exists(runDirectory)) {
            throw new IllegalStateException("Retrieval evidence run directory is unsafe or already exists");
        }
        Files.createDirectories(runDirectory.resolve("raw"));

        writeJson(runDirectory.resolve("manifest.json"), manifest(
                startedAt, candidateRowCount, deduplicatedQueryGroups, observations));
        writeJson(runDirectory.resolve("environment.json"), environment(mysqlImage, elasticsearchImage));
        writeJson(runDirectory.resolve("raw/observations.json"), observations);
        writeJson(runDirectory.resolve("summary.json"), summary(observations, results));
        Files.writeString(runDirectory.resolve("summary.md"), summaryMarkdown(results), StandardCharsets.UTF_8);
        Files.writeString(runDirectory.resolve("failures.md"), failuresMarkdown(observations), StandardCharsets.UTF_8);
        writeChecksums(runDirectory);
    }

    private ObjectNode manifest(Instant startedAt,
                                int candidateRowCount,
                                int deduplicatedQueryGroups,
                                List<CandidateObservation> observations) {
        long answerable = observations.stream().filter(CandidateObservation::proposedAnswerExists).count();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schemaVersion", 1)
                .put("runId", requiredProperty("retrieval.candidate.evidence.run-id"))
                .put("status", "COMPLETED")
                .put("evidenceScope", "CANDIDATE_DIAGNOSTIC_ONLY")
                .put("evidenceStatus", "CANDIDATE_DIAGNOSTIC_ONLY")
                .put("labelStatus", LABEL_STATUS)
                .put("reviewStatus", REVIEW_STATUS)
                .put("formalGold", false)
                .put("qualityEvidence", false)
                .put("semanticQualityEvidence", false)
                .put("subjectCommit", commitProperty("retrieval.candidate.evidence.subject-commit"))
                .put("executionCommit", commitProperty("retrieval.candidate.evidence.execution-commit"))
                .put("harnessCommit", commitProperty("retrieval.candidate.evidence.harness-commit"))
                .put("datasetCommit", commitProperty("retrieval.candidate.evidence.dataset-commit"))
                .put("datasetVersion", "agent-evaluation-v1")
                .put("candidateDatasetVersion", "retrieval-candidates-v1")
                .put("candidateRowCount", candidateRowCount)
                .put("queryObservationCount", observations.size())
                .put("deduplicatedQueryGroups", deduplicatedQueryGroups)
                .put("proposedAnswerableCount", answerable)
                .put("proposedNoAnswerCount", observations.size() - answerable)
                .put("externalModelCalls", 0)
                .put("startedAt", startedAt.toString())
                .put("endedAt", Instant.now().toString());
        node.putArray("retrievalModes")
                .add("keyword-only").add("vector-only").add("three-way-document-rrf");
        return node;
    }

    private Map<String, Object> environment(String mysqlImage, String elasticsearchImage) {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("infrastructureMode", "REAL_TESTCONTAINERS");
        environment.put("mysqlImage", mysqlImage);
        environment.put("elasticsearchImage", elasticsearchImage);
        environment.put("contentMode", "REAL_LOCAL_FILESYSTEM_HTTP");
        environment.put("corpusMode", "DETERMINISTIC_CANDIDATE_FIXTURE_V1");
        environment.put("embeddingMode", "DETERMINISTIC_LOCAL_HASH_V1");
        environment.put("entityResolver", "DETERMINISTIC_BANGUMI_TEST_ADAPTER");
        environment.put("chatMode", "NOT_INVOKED_INERT_TEST_DOUBLE");
        environment.put("externalModelCalls", 0);
        environment.put("semanticQualityEvidence", false);
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        return environment;
    }

    private Map<String, Object> summary(List<CandidateObservation> observations,
                                        Map<String, RetrievalCandidateMetrics.Result> results) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "COMPLETED");
        summary.put("labelStatus", LABEL_STATUS);
        summary.put("reviewStatus", REVIEW_STATUS);
        summary.put("formalGold", false);
        summary.put("queryObservationCount", observations.size());
        Map<String, Object> metrics = new LinkedHashMap<>();
        results.forEach((name, result) -> metrics.put(name, requestedMetrics(result)));
        summary.put("metrics", metrics);
        summary.put("citationObservation", "NOT_OBSERVED_NO_GENERATION");
        return summary;
    }

    private Map<String, Object> requestedMetrics(RetrievalCandidateMetrics.Result result) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("recallAt5", result.recallAt5());
        metrics.put("mrr", result.mrr());
        metrics.put("citationValidityRate", result.citationValidityRate());
        metrics.put("noAnswerAccuracy", result.noAnswerAccuracy());
        return metrics;
    }

    private String summaryMarkdown(Map<String, RetrievalCandidateMetrics.Result> results) {
        StringBuilder markdown = new StringBuilder("# 检索候选诊断\n\n")
                .append("标签：CANDIDATE_REQUIRES_OWNER_REVIEW；复核状态：COLLECTED_UNREVIEWED。\n\n")
                .append("确定性候选语料和本地 hash embedding 仅证明管线行为，不是语义质量证据。\n\n")
                .append("| 路径 | Recall@5 | MRR | 引用合法率 | 无答案正确率 |\n")
                .append("|---|---:|---:|---:|---:|\n");
        results.forEach((name, result) -> markdown.append("| ").append(name).append(" | ")
                .append(result.recallAt5()).append(" | ").append(result.mrr()).append(" | ")
                .append(result.citationValidityRate() == null ? "NOT_OBSERVED" : result.citationValidityRate())
                .append(" | ").append(result.noAnswerAccuracy()).append(" |\n"));
        return markdown.toString();
    }

    private String failuresMarkdown(List<CandidateObservation> observations) {
        StringBuilder markdown = new StringBuilder("# 候选失败样本\n\n")
                .append("以下判断仅相对于未经人工复核的 proposed label。\n\n")
                .append("3 组重复 query 已合并相关文档集合：retrieval-0001/0011、retrieval-0051/0061、retrieval-0091/0101。\n\n")
                .append("未运行回答生成，因此引用合法率为 NOT_OBSERVED。\n");
        observations.forEach(observation -> appendFailures(markdown, observation));
        return markdown.toString();
    }

    private void appendFailures(StringBuilder markdown, CandidateObservation observation) {
        List<Map.Entry<String, RouteObservation>> routes = List.of(
                Map.entry("keyword-only", observation.keywordOnly()),
                Map.entry("vector-only", observation.vectorOnly()),
                Map.entry("three-way-document-rrf", observation.documentRrf3()));
        routes.forEach(entry -> {
            String name = entry.getKey();
            RouteObservation route = entry.getValue();
            boolean failed = observation.proposedAnswerExists()
                    ? !route.rankedDocumentIds().stream().limit(5).toList()
                            .containsAll(observation.proposedDocumentIds())
                    : !route.noAnswer();
            if (failed) {
                markdown.append("\n- ").append(String.join("+", observation.sampleIds()))
                        .append(" / ").append(name);
            }
        });
    }

    private void writeChecksums(Path runDirectory) throws Exception {
        List<Path> files;
        try (var paths = Files.walk(runDirectory)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("checksums.sha256"))
                    .sorted()
                    .toList();
        }
        StringBuilder checksums = new StringBuilder();
        for (Path file : files) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            checksums.append(HexFormat.of().formatHex(digest)).append("  ")
                    .append(runDirectory.relativize(file).toString().replace('\\', '/')).append('\n');
        }
        Files.writeString(runDirectory.resolve("checksums.sha256"), checksums, StandardCharsets.UTF_8);
    }

    private void writeJson(Path path, Object value) throws Exception {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) { throw new IllegalArgumentException(name + " is required"); }
        return value;
    }

    private static String commitProperty(String name) {
        String value = requiredProperty(name);
        if (!value.matches("[0-9a-f]{40}")) { throw new IllegalArgumentException(name + " must be a full commit"); }
        return value;
    }

    record CandidateObservation(List<String> sampleIds,
                                boolean proposedAnswerExists,
                                List<String> proposedDocumentIds,
                                RouteObservation keywordOnly,
                                RouteObservation vectorOnly,
                                RouteObservation documentRrf3) { }

    record RouteObservation(List<String> rankedDocumentIds,
                            boolean noAnswer,
                            Map<String, String> statuses) { }
}
