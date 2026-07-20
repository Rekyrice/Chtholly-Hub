package com.chtholly.agent.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.chtholly.integration.AbstractGoldenPathIT;
import com.chtholly.llm.rag.RagIndexService;
import com.chtholly.llm.rag.RagQueryService;
import com.chtholly.search.index.SearchIndexService;
import com.chtholly.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RetrievalInfrastructureTestConfig.class)
class HybridSearchRealInfrastructureIT extends AbstractGoldenPathIT {

    private static final String SEARCH_INDEX = "chtholly_content_index";
    private static final long AUTHOR_ID = 930000000000000001L;
    private static final long PUBLIC_POST_ID = 930000000000000011L;
    private static final long PRIVATE_POST_ID = 930000000000000012L;
    private static final long STALE_POST_ID = 930000000000000013L;
    private static final Path STORAGE_PATH = Path.of("target", "retrieval-it-storage")
            .toAbsolutePath().normalize();

    @LocalServerPort
    private int serverPort;

    @Autowired
    private StorageService storageService;
    @Autowired
    private SearchIndexService searchIndexService;
    @Autowired
    private RagIndexService ragIndexService;
    @Autowired
    private RagQueryService ragQueryService;
    @Autowired
    private HybridSearchService hybridSearchService;
    @Autowired
    private ElasticsearchClient elasticsearchClient;
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private CountingInertChatModel chatModel;

    @DynamicPropertySource
    static void retrievalProperties(DynamicPropertyRegistry registry) {
        registry.add("llm.enabled", () -> "true");
        registry.add("rag.enabled", () -> "true");
        registry.add("bangumi.enabled", () -> "false");
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
                AUTHOR_ID, "检索夹具", "retrieval-fixture", "USER");
    }

    @Test
    void fusesRealMysqlFulltextVectorAndEntityRoutesAtAuthorizedDocumentIdentity() throws Exception {
        StoredPost publicPost = storePost(PUBLIC_POST_ID, "public", """
                # 原子恢复
                原子恢复要求权威状态与派生计数最终收敛。

                ## 重复片段
                原子恢复链路必须能够确定性重放，但同一文章只能获得一张检索票。
                """);
        StoredPost privatePost = storePost(PRIVATE_POST_ID, "private", """
                # 私有原子恢复
                该私有正文不得进入最终 Evidence。
                """);
        StoredPost stalePost = storePost(STALE_POST_ID, "public", """
                # 旧版本
                当前正文不等于向量索引里的历史指纹。
                """);

        assertThat(searchIndexService.tryUpsertPost(publicPost.id())).isTrue();
        assertThat(searchIndexService.tryUpsertPost(privatePost.id())).isTrue();
        addEntity(publicPost.id());
        addEntity(privatePost.id());
        assertThat(ragIndexService.reindexSinglePost(publicPost.id())).isGreaterThanOrEqualTo(2);
        addRejectedSemanticCandidates(privatePost, stalePost);
        List<Document> rawSemantic = vectorStore.similaritySearch(
                SearchRequest.builder().query("原子恢复").topK(10).build());
        assertThat(rawSemantic).isNotEmpty();
        assertThat(rawSemantic)
                .extracting(document -> String.valueOf(document.getMetadata().get("postId")))
                .contains(
                        String.valueOf(PUBLIC_POST_ID),
                        String.valueOf(PRIVATE_POST_ID),
                        String.valueOf(STALE_POST_ID));
        assertThat(ragQueryService.search("原子恢复", 10))
                .as("authorized semantic results from raw documents %s", rawSemantic)
                .isNotEmpty()
                .allMatch(result -> ("post:" + PUBLIC_POST_ID).equals(result.getDocumentId()));

        HybridSearchService.RetrievalSnapshot first =
                hybridSearchService.retrievalSnapshot("原子恢复", 5);
        HybridSearchService.RetrievalSnapshot replay =
                hybridSearchService.retrievalSnapshot("原子恢复", 5);

        assertSuccessfulRoutes(first);
        assertThat(ids(first.semanticDocuments())).containsExactly("post:" + PUBLIC_POST_ID);
        assertThat(ids(first.keywordDocuments())).containsExactly("post:" + PUBLIC_POST_ID);
        assertThat(ids(first.entityDocuments())).containsExactly("post:" + PUBLIC_POST_ID);
        assertThat(ids(first.response().documents())).containsExactly("post:" + PUBLIC_POST_ID);
        assertThat(first.response().documents().getFirst().getSource())
                .isEqualTo("semantic+keyword+entity");
        assertThat(first.response().documents().getFirst().getPermissions()).containsExactly("PUBLIC");
        assertThat(first.response().documents().getFirst().getSourceHash()).isEqualTo(publicPost.sha256());
        assertThat(snapshot(first)).isEqualTo(snapshot(replay));
        assertThat(vectorChunkCount(publicPost.id())).isGreaterThanOrEqualTo(2);
        assertThat(chatModel.calls()).isZero();
    }

    private StoredPost storePost(long id, String visible, String markdown) throws Exception {
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String objectKey = "retrieval-it/" + id + "-" + sha256 + ".md";
        storageService.uploadVerifiedObject(
                objectKey, new ByteArrayInputStream(bytes), "text/markdown", bytes.length, sha256);
        String contentUrl = "http://127.0.0.1:" + serverPort + storageService.resolvePublicUrl(objectKey);
        jdbc.update("""
                INSERT INTO posts (
                    id, tags, title, slug, description, content_url, content_object_key,
                    content_etag, content_size, content_sha256, creator_id, type, visible,
                    status, publish_time, img_urls)
                VALUES (?, JSON_ARRAY('retrieval'), ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'image_text', ?, 'published', CURRENT_TIMESTAMP, JSON_ARRAY())
                """, id, "原子恢复 " + id, "retrieval-" + id, "真实检索夹具", contentUrl,
                objectKey, sha256, bytes.length, sha256, AUTHOR_ID, visible);
        return new StoredPost(id, sha256);
    }

    private void addEntity(long postId) throws Exception {
        Map<String, Object> contentAnalysis = Map.of(
                "entities", List.of(Map.of(
                        "name", "原子恢复", "category", "TEST_ENTITY", "confidence", 1.0)));
        elasticsearchClient.update(request -> request
                        .index(SEARCH_INDEX)
                        .id(String.valueOf(postId))
                        .doc(Map.of("contentAnalysis", contentAnalysis))
                        .refresh(Refresh.WaitFor),
                Map.class);
    }

    private void addRejectedSemanticCandidates(StoredPost privatePost, StoredPost stalePost) throws Exception {
        vectorStore.add(List.of(
                vectorDocument(privatePost.id(), privatePost.sha256(), "私有原子恢复证据"),
                vectorDocument(stalePost.id(), "0".repeat(64), "陈旧原子恢复证据")));
        elasticsearchClient.indices().refresh(request -> request
                .index(RetrievalInfrastructureTestConfig.VECTOR_INDEX));
    }

    private Document vectorDocument(long postId, String sha256, String text) {
        return new Document(text, Map.of(
                "postId", String.valueOf(postId),
                "chunkId", postId + "#manual",
                "contentSha256", sha256,
                "title", "拒绝候选"));
    }

    private void assertSuccessfulRoutes(HybridSearchService.RetrievalSnapshot snapshot) {
        assertThat(snapshot.response().statuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "semantic", HybridSearchService.RetrievalStatus.SUCCESS_RESULTS,
                "keyword", HybridSearchService.RetrievalStatus.SUCCESS_RESULTS,
                "entity", HybridSearchService.RetrievalStatus.SUCCESS_RESULTS));
        assertThat(snapshot.response().degraded()).isFalse();
    }

    private List<String> ids(List<SearchResult> results) {
        return results.stream().map(SearchResult::getDocumentId).toList();
    }

    private List<String> snapshot(HybridSearchService.RetrievalSnapshot snapshot) {
        return snapshot.response().documents().stream()
                .map(result -> result.getDocumentId() + "|" + result.getSource() + "|" + result.getScore())
                .toList();
    }

    private long vectorChunkCount(long postId) throws Exception {
        return elasticsearchClient.count(request -> request
                .index(RetrievalInfrastructureTestConfig.VECTOR_INDEX)
                .query(query -> query.term(term -> term
                        .field("metadata.postId")
                        .value(String.valueOf(postId))))).count();
    }

    private void clearIndex(String index) throws Exception {
        if (elasticsearchClient.indices().exists(request -> request.index(index)).value()) {
            elasticsearchClient.deleteByQuery(request -> request
                    .index(index)
                    .query(query -> query.matchAll(matchAll -> matchAll))
                    .refresh(true));
        }
    }

    private record StoredPost(long id, String sha256) {
    }
}
