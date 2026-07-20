package com.chtholly.agent.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.chtholly.integration.AbstractGoldenPathIT;
import com.chtholly.llm.rag.RagIndexService;
import com.chtholly.search.index.SearchIndexService;
import com.chtholly.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Collects 45 non-formal retrieval diagnostics on real storage, MySQL, and Elasticsearch. */
@EnabledIfSystemProperty(named = "retrieval.candidate.evidence.enabled", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RetrievalInfrastructureTestConfig.class)
class RetrievalCandidateEvidenceCollectorIT extends AbstractGoldenPathIT {

    private static final String REVIEW_STATUS = "CANDIDATE_REQUIRES_OWNER_REVIEW";
    private static final String SEARCH_INDEX = "chtholly_content_index";
    private static final long AUTHOR_ID = 940000000000000001L;
    private static final long FIRST_POST_ID = 940000000000001000L;
    private static final Path STORAGE_PATH = Path.of("target", "retrieval-candidate-storage")
            .toAbsolutePath().normalize();

    @LocalServerPort private int serverPort;
    @Autowired private StorageService storageService;
    @Autowired private SearchIndexService searchIndexService;
    @Autowired private RagIndexService ragIndexService;
    @Autowired private HybridSearchService hybridSearchService;
    @Autowired private ElasticsearchClient elasticsearchClient;
    @Autowired private CountingInertChatModel chatModel;

    @DynamicPropertySource
    static void retrievalProperties(DynamicPropertyRegistry registry) {
        registry.add("llm.enabled", () -> "true");
        registry.add("rag.enabled", () -> "true");
        registry.add("bangumi.enabled", () -> "false");
        registry.add("agent.extensions.content.enabled", () -> "false");
        registry.add("storage.local.base-path", STORAGE_PATH::toString);
        registry.add("spring.ai.vectorstore.elasticsearch.index-name",
                () -> RetrievalInfrastructureTestConfig.VECTOR_INDEX);
        registry.add("spring.ai.vectorstore.elasticsearch.dimensions",
                () -> DeterministicTestEmbeddingModel.DIMENSIONS);
    }

    @BeforeEach
    void resetFixture() throws Exception {
        cleanDatabase();
        cleanRedis();
        clearIndex(SEARCH_INDEX);
        clearIndex(RetrievalInfrastructureTestConfig.VECTOR_INDEX);
        jdbc.update("INSERT INTO users (id, nickname, handle, role) VALUES (?, ?, ?, ?)",
                AUTHOR_ID, "检索候选采集", "retrieval-candidate", "USER");
    }

    @Test
    void collectsKeywordVectorAndThreeRouteDocumentRrfDiagnostics() throws Exception {
        Instant startedAt = Instant.now();
        List<Candidate> candidates = readCandidates();
        assertThat(candidates).hasSize(45);
        assertThat(candidates).allMatch(candidate -> REVIEW_STATUS.equals(candidate.labelStatus()));
        List<CandidateGroup> groups = groupByExactQuery(candidates);
        assertThat(groups).hasSizeBetween(40, 50).hasSize(42);

        Map<String, Candidate> documentOwners = documentOwners(candidates);
        Map<String, String> actualToLogical = seedCorpus(documentOwners);
        List<RetrievalCandidateEvidenceWriter.CandidateObservation> observations = new ArrayList<>();
        for (CandidateGroup group : groups) {
            HybridSearchService.RetrievalSnapshot snapshot =
                    hybridSearchService.retrievalSnapshot(group.query(), 5);
            assertThat(snapshot.response().statuses().values())
                    .doesNotContain(HybridSearchService.RetrievalStatus.FAILED,
                            HybridSearchService.RetrievalStatus.TIMEOUT);
            observations.add(observation(group, snapshot, actualToLogical));
        }

        Map<String, RetrievalCandidateMetrics.Result> results = new LinkedHashMap<>();
        results.put("keyword-only", evaluate(observations,
                RetrievalCandidateEvidenceWriter.CandidateObservation::keywordOnly));
        results.put("vector-only", evaluate(observations,
                RetrievalCandidateEvidenceWriter.CandidateObservation::vectorOnly));
        results.put("three-way-document-rrf", evaluate(observations,
                RetrievalCandidateEvidenceWriter.CandidateObservation::documentRrf3));
        assertThat(results.values()).allSatisfy(result -> {
            assertThat(result.formalGold()).isFalse();
            assertThat(result.citationValidityRate()).isNull();
        });
        assertThat(chatModel.calls()).isZero();

        new RetrievalCandidateEvidenceWriter(objectMapper).write(
                startedAt, candidates.size(), Math.toIntExact(candidates.size() - groups.size()),
                observations, results, MYSQL.getDockerImageName(), ELASTICSEARCH.getDockerImageName());
    }

    private List<Candidate> readCandidates() throws Exception {
        Path dataset = Path.of(requiredProperty("retrieval.candidate.evidence.repo-root"))
                .resolve("benchmarks/datasets/agent-evaluation/retrieval.jsonl");
        try (Stream<String> lines = Files.lines(dataset, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).map(this::candidate).toList();
        }
    }

    private Candidate candidate(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            List<String> documentIds = new ArrayList<>();
            node.path("proposedDocumentIds").forEach(value -> documentIds.add(value.asText()));
            JsonNode context = node.path("scenarioContext");
            return new Candidate(
                    node.path("sampleId").asText(),
                    node.path("query").asText(),
                    node.path("normalizedIntent").asText(),
                    node.path("labelStatus").asText(),
                    node.path("proposedAnswerExists").asBoolean(),
                    List.copyOf(documentIds),
                    context.path("publicFact").asText(),
                    context.path("untrustedInstruction").asText());
        } catch (Exception exception) {
            throw new IllegalStateException("Retrieval candidate is invalid", exception);
        }
    }

    private Map<String, Candidate> documentOwners(List<Candidate> candidates) {
        Map<String, Candidate> owners = new LinkedHashMap<>();
        candidates.forEach(candidate -> candidate.proposedDocumentIds()
                .forEach(documentId -> owners.putIfAbsent(documentId, candidate)));
        return owners;
    }

    private List<CandidateGroup> groupByExactQuery(List<Candidate> candidates) {
        Map<String, List<Candidate>> grouped = new LinkedHashMap<>();
        candidates.forEach(candidate -> grouped
                .computeIfAbsent(candidate.query(), ignored -> new ArrayList<>()).add(candidate));
        return grouped.values().stream().map(group -> {
            Set<Boolean> answers = group.stream().map(Candidate::proposedAnswerExists).collect(
                    java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (answers.size() != 1) {
                throw new IllegalStateException("Duplicate query has conflicting proposed answer existence");
            }
            List<String> sampleIds = group.stream().map(Candidate::sampleId).toList();
            List<String> documents = group.stream().flatMap(candidate -> candidate.proposedDocumentIds().stream())
                    .distinct().toList();
            return new CandidateGroup(sampleIds, group.getFirst().query(), answers.iterator().next(), documents);
        }).toList();
    }

    private Map<String, String> seedCorpus(Map<String, Candidate> documentOwners) throws Exception {
        Map<String, String> actualToLogical = new LinkedHashMap<>();
        long postId = FIRST_POST_ID;
        for (Map.Entry<String, Candidate> entry : documentOwners.entrySet()) {
            storeAndIndex(postId, entry.getKey(), entry.getValue());
            actualToLogical.put("post:" + postId, entry.getKey());
            postId++;
        }
        return actualToLogical;
    }

    private void storeAndIndex(long postId, String logicalId, Candidate candidate) throws Exception {
        String markdown = "# " + candidate.normalizedIntent() + "\n\n" + candidate.publicFact()
                + "\n\n来源候选：" + candidate.sampleId()
                + (candidate.untrustedInstruction().isBlank() ? "" : "\n\n不可信页面文本：" + candidate.untrustedInstruction());
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String objectKey = "retrieval-candidates/" + postId + "-" + sha256 + ".md";
        storageService.uploadVerifiedObject(
                objectKey, new ByteArrayInputStream(bytes), "text/markdown", bytes.length, sha256);
        String contentUrl = "http://127.0.0.1:" + serverPort + storageService.resolvePublicUrl(objectKey);
        jdbc.update("""
                INSERT INTO posts (
                    id, tags, title, slug, description, content_url, content_object_key,
                    content_etag, content_size, content_sha256, creator_id, type, visible,
                    status, publish_time, img_urls)
                VALUES (?, JSON_ARRAY('retrieval-candidate'), ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'image_text', 'public', 'published', CURRENT_TIMESTAMP, JSON_ARRAY())
                """, postId, candidate.normalizedIntent(), "candidate-" + postId, logicalId, contentUrl,
                objectKey, sha256, bytes.length, sha256, AUTHOR_ID);
        assertThat(searchIndexService.tryUpsertPost(postId)).isTrue();
        addEntity(postId, RetrievalInfrastructureTestConfig.entityName(candidate.query()));
        assertThat(ragIndexService.reindexSinglePost(postId)).isPositive();
    }

    private void addEntity(long postId, String entityName) throws Exception {
        if (entityName.isBlank()) {
            return;
        }
        Map<String, Object> contentAnalysis = Map.of(
                "entities", List.of(Map.of(
                        "name", entityName, "category", "CANDIDATE_QUERY", "confidence", 1.0)));
        elasticsearchClient.update(request -> request.index(SEARCH_INDEX).id(String.valueOf(postId))
                        .doc(Map.of("contentAnalysis", contentAnalysis)).refresh(Refresh.WaitFor), Map.class);
    }

    private RetrievalCandidateEvidenceWriter.CandidateObservation observation(
            CandidateGroup group,
            HybridSearchService.RetrievalSnapshot snapshot,
            Map<String, String> actualToLogical) {
        Map<String, String> statuses = new LinkedHashMap<>();
        snapshot.response().statuses().forEach((name, status) -> statuses.put(name, status.name()));
        return new RetrievalCandidateEvidenceWriter.CandidateObservation(
                group.sampleIds(), group.proposedAnswerExists(), group.proposedDocumentIds(),
                route(snapshot.keywordDocuments(), actualToLogical, Map.of("keyword", statuses.get("keyword"))),
                route(snapshot.semanticDocuments(), actualToLogical, Map.of("semantic", statuses.get("semantic"))),
                route(snapshot.response().documents(), actualToLogical, statuses));
    }

    private RetrievalCandidateEvidenceWriter.RouteObservation route(
            List<SearchResult> results, Map<String, String> actualToLogical, Map<String, String> statuses) {
        List<String> ranked = results.stream()
                .map(SearchResult::getDocumentId)
                .map(documentId -> actualToLogical.getOrDefault(documentId, documentId))
                .distinct()
                .limit(5)
                .toList();
        return new RetrievalCandidateEvidenceWriter.RouteObservation(ranked, ranked.isEmpty(), statuses);
    }

    private RetrievalCandidateMetrics.Result evaluate(
            List<RetrievalCandidateEvidenceWriter.CandidateObservation> observations,
            java.util.function.Function<RetrievalCandidateEvidenceWriter.CandidateObservation,
                    RetrievalCandidateEvidenceWriter.RouteObservation> route) {
        List<RetrievalCandidateMetrics.Observation> metrics = observations.stream().map(observation -> {
            RetrievalCandidateEvidenceWriter.RouteObservation selected = route.apply(observation);
            return new RetrievalCandidateMetrics.Observation(
                    new LinkedHashSet<>(observation.proposedDocumentIds()),
                    observation.proposedAnswerExists(), selected.rankedDocumentIds(),
                    List.of(), Set.of(), selected.noAnswer());
        }).toList();
        return RetrievalCandidateMetrics.evaluate(REVIEW_STATUS, metrics);
    }

    private void clearIndex(String index) throws Exception {
        if (elasticsearchClient.indices().exists(request -> request.index(index)).value()) {
            elasticsearchClient.deleteByQuery(request -> request.index(index)
                    .query(query -> query.matchAll(matchAll -> matchAll)).refresh(true));
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) { throw new IllegalArgumentException(name + " is required"); }
        return value;
    }

    private record Candidate(String sampleId, String query, String normalizedIntent, String labelStatus,
                             boolean proposedAnswerExists, List<String> proposedDocumentIds,
                             String publicFact, String untrustedInstruction) { }

    private record CandidateGroup(List<String> sampleIds, String query, boolean proposedAnswerExists,
                                  List<String> proposedDocumentIds) { }
}
