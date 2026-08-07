package com.chtholly.llm.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch._types.BulkIndexByScrollFailure;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.RefreshResponse;
import co.elastic.clients.elasticsearch._types.ShardStatistics;
import com.chtholly.config.EsProperties;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostDetailRow;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagIndexServiceTest {

    private HttpServer contentServer;

    @BeforeEach
    void startContentServer() throws IOException {
        contentServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        contentServer.createContext("/post.md", exchange -> {
            byte[] body = "# 可见性测试\n向量写入后必须立即可检索。".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        contentServer.start();
    }

    @AfterEach
    void stopContentServer() {
        contentServer.stop(0);
    }

    @Test
    void refreshFailurePropagatesAfterVectorWrite() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        PostMapper postMapper = mock(PostMapper.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        EsProperties properties = new EsProperties();
        properties.setIndex("rag-test-index");

        when(postMapper.findDetailById(42L)).thenReturn(publishedPost());
        when(elasticsearch.get(any(java.util.function.Function.class), eq(java.util.Map.class)))
                .thenThrow(new IOException("index unavailable"));
        doReturn(completeDeleteResponse()).when(elasticsearch)
                .deleteByQuery(any(java.util.function.Function.class));
        when(elasticsearch.indices()).thenReturn(indices);
        when(indices.refresh(any(java.util.function.Function.class)))
                .thenThrow(new IOException("refresh unavailable"));

        RagIndexService service = new RagIndexService(
                vectorStore, postMapper, passThroughLock(), new RestTemplate(),
                elasticsearch, properties);

        assertThatThrownBy(() -> service.reindexSinglePost(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refresh");
    }

    @Test
    void partialShardRefreshCannotPublishACompletionManifest() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        PostMapper postMapper = mock(PostMapper.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        RefreshResponse refreshResponse = mock(RefreshResponse.class);
        ShardStatistics shards = mock(ShardStatistics.class);
        EsProperties properties = new EsProperties();
        properties.setIndex("rag-test-index");

        when(postMapper.findDetailById(42L)).thenReturn(publishedPost());
        when(elasticsearch.get(any(java.util.function.Function.class), eq(Map.class)))
                .thenThrow(new IOException("force rebuild"));
        doReturn(completeDeleteResponse()).when(elasticsearch)
                .deleteByQuery(any(java.util.function.Function.class));
        when(elasticsearch.indices()).thenReturn(indices);
        when(indices.refresh(any(java.util.function.Function.class)))
                .thenReturn(refreshResponse);
        when(refreshResponse.shards()).thenReturn(shards);
        when(shards.failed()).thenReturn(1);

        RagIndexService service = new RagIndexService(
                vectorStore, postMapper, passThroughLock(), new RestTemplate(),
                elasticsearch, properties);

        assertThatThrownBy(() -> service.reindexSinglePost(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete vector index refresh");
        verify(vectorStore).add(any());
        verify(elasticsearch, never()).index(any(java.util.function.Function.class));
    }

    @Test
    void privatePostRemovesStaleChunksAndRefreshesTheirVisibility() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        PostMapper postMapper = mock(PostMapper.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        EsProperties properties = new EsProperties();
        properties.setIndex("rag-test-index");
        PostDetailRow privatePost = publishedPost();
        privatePost.setVisible("private");

        when(postMapper.findDetailById(42L)).thenReturn(privatePost);
        doReturn(completeDeleteResponse()).when(elasticsearch)
                .deleteByQuery(any(java.util.function.Function.class));
        when(elasticsearch.indices()).thenReturn(indices);
        doReturn(completeRefreshResponse()).when(indices)
                .refresh(any(java.util.function.Function.class));

        RagIndexService service = new RagIndexService(
                vectorStore, postMapper, passThroughLock(), new RestTemplate(),
                elasticsearch, properties);

        service.ensureIndexed(42L);

        verify(elasticsearch).deleteByQuery(any(java.util.function.Function.class));
        verify(indices).refresh(any(java.util.function.Function.class));
        verify(vectorStore, never()).add(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingCompletionManifestDoesNotBlockFirstIndexBuild() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        PostMapper postMapper = mock(PostMapper.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        GetResponse<Map> missingManifest = mock(GetResponse.class);
        ElasticsearchException notFound = new ElasticsearchException(
                "es/delete",
                ErrorResponse.of(error -> error
                        .status(404)
                        .error(cause -> cause
                                .type("document_missing_exception")
                                .reason("manifest not found"))));
        EsProperties properties = new EsProperties();
        properties.setIndex("rag-test-index");

        when(postMapper.findDetailById(42L)).thenReturn(publishedPost());
        when(elasticsearch.get(any(java.util.function.Function.class), eq(Map.class)))
                .thenReturn(missingManifest);
        when(missingManifest.found()).thenReturn(false);
        when(elasticsearch.delete(any(java.util.function.Function.class)))
                .thenThrow(notFound);
        doReturn(completeDeleteResponse()).when(elasticsearch)
                .deleteByQuery(any(java.util.function.Function.class));
        when(elasticsearch.indices()).thenReturn(indices);
        doReturn(completeRefreshResponse()).when(indices)
                .refresh(any(java.util.function.Function.class));

        RagIndexService service = new RagIndexService(
                vectorStore, postMapper, passThroughLock(), new RestTemplate(),
                elasticsearch, properties);

        assertThat(service.reindexSinglePost(42L)).isPositive();

        verify(elasticsearch).deleteByQuery(any(java.util.function.Function.class));
        verify(vectorStore).add(any());
        verify(elasticsearch).index(any(java.util.function.Function.class));
    }

    @Test
    void vectorWriteFailurePropagatesSoOutboxCannotAcknowledgeTheEvent() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        PostMapper postMapper = mock(PostMapper.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        EsProperties properties = new EsProperties();
        properties.setIndex("rag-test-index");

        when(postMapper.findDetailById(42L)).thenReturn(publishedPost());
        when(elasticsearch.get(any(java.util.function.Function.class), eq(java.util.Map.class)))
                .thenThrow(new IOException("index unavailable"));
        doReturn(completeDeleteResponse()).when(elasticsearch)
                .deleteByQuery(any(java.util.function.Function.class));
        when(elasticsearch.indices()).thenReturn(indices);
        doThrow(new IllegalStateException("embedding unavailable"))
                .when(vectorStore).add(any());

        RagIndexService service = new RagIndexService(
                vectorStore, postMapper, passThroughLock(), new RestTemplate(),
                elasticsearch, properties);

        assertThatThrownBy(() -> service.ensureIndexed(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vector");
    }

    @Test
    void staleChunkDeletionFailurePropagatesBeforeVectorWrite() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        PostMapper postMapper = mock(PostMapper.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        EsProperties properties = new EsProperties();
        properties.setIndex("rag-test-index");

        when(postMapper.findDetailById(42L)).thenReturn(publishedPost());
        when(elasticsearch.get(any(java.util.function.Function.class), eq(java.util.Map.class)))
                .thenThrow(new IOException("index unavailable"));
        when(elasticsearch.deleteByQuery(any(java.util.function.Function.class)))
                .thenThrow(new IOException("delete unavailable"));

        RagIndexService service = new RagIndexService(
                vectorStore, postMapper, passThroughLock(), new RestTemplate(),
                elasticsearch, properties);

        assertThatThrownBy(() -> service.ensureIndexed(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delete");
        verify(vectorStore, never()).add(any());
    }

    @Test
    void timedOutChunkDeletionCannotPublishACompletionManifest() throws Exception {
        DeleteByQueryResponse response = mock(DeleteByQueryResponse.class);
        when(response.timedOut()).thenReturn(true);
        when(response.failures()).thenReturn(List.of());
        when(response.versionConflicts()).thenReturn(0L);

        assertIncompleteChunkDeletionFails(response);
    }

    @Test
    void chunkDeletionFailuresCannotPublishACompletionManifest() throws Exception {
        DeleteByQueryResponse response = mock(DeleteByQueryResponse.class);
        when(response.timedOut()).thenReturn(false);
        when(response.failures()).thenReturn(List.of(
                mock(BulkIndexByScrollFailure.class)));
        when(response.versionConflicts()).thenReturn(0L);

        assertIncompleteChunkDeletionFails(response);
    }

    @Test
    void chunkDeletionVersionConflictsCannotPublishACompletionManifest() throws Exception {
        DeleteByQueryResponse response = mock(DeleteByQueryResponse.class);
        when(response.timedOut()).thenReturn(false);
        when(response.failures()).thenReturn(List.of());
        when(response.versionConflicts()).thenReturn(1L);

        assertIncompleteChunkDeletionFails(response);
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentsUseStableBusinessIdsInsteadOfGeneratedIds() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        PostMapper postMapper = mock(PostMapper.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        EsProperties properties = new EsProperties();
        properties.setIndex("rag-test-index");

        when(postMapper.findDetailById(42L)).thenReturn(publishedPost());
        when(elasticsearch.get(any(java.util.function.Function.class), eq(java.util.Map.class)))
                .thenThrow(new IOException("force rebuild"));
        doReturn(completeDeleteResponse()).when(elasticsearch)
                .deleteByQuery(any(java.util.function.Function.class));
        when(elasticsearch.indices()).thenReturn(indices);
        doReturn(completeRefreshResponse()).when(indices)
                .refresh(any(java.util.function.Function.class));

        RagIndexService service = new RagIndexService(
                vectorStore, postMapper, passThroughLock(), new RestTemplate(),
                elasticsearch, properties);

        service.reindexSinglePost(42L);
        service.reindexSinglePost(42L);

        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, org.mockito.Mockito.times(2)).add(documents.capture());
        List<Document> first = documents.getAllValues().get(0);
        List<Document> second = documents.getAllValues().get(1);
        assertThat(first).isNotEmpty();
        assertThat(first).extracting(Document::getId)
                .containsExactlyElementsOf(second.stream().map(Document::getId).toList());
        assertThat(first.getFirst().getId()).startsWith("post:42:");
        assertThat(first.getFirst().getMetadata().get("chunkId"))
                .isEqualTo(first.getFirst().getId());
    }

    @Test
    void injectedContentClientFailurePropagatesBeforeDeletingOrWritingVectors() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        PostMapper postMapper = mock(PostMapper.class);
        RestTemplate contentClient = mock(RestTemplate.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        EsProperties properties = new EsProperties();
        properties.setIndex("rag-test-index");
        PostDetailRow post = publishedPost();

        when(postMapper.findDetailById(42L)).thenReturn(post);
        when(elasticsearch.get(any(java.util.function.Function.class), eq(java.util.Map.class)))
                .thenThrow(new IOException("force rebuild"));
        when(contentClient.getForObject(post.getContentUrl(), String.class))
                .thenThrow(new ResourceAccessException("read timed out"));

        RagIndexService service = new RagIndexService(
                vectorStore, postMapper, passThroughLock(), contentClient,
                elasticsearch, properties);

        assertThatThrownBy(() -> service.reindexSinglePost(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content fetch")
                .hasRootCauseInstanceOf(ResourceAccessException.class);
        verify(elasticsearch, never()).deleteByQuery(any(java.util.function.Function.class));
        verify(vectorStore, never()).add(any());
    }

    @Test
    void relativeLocalContentUrlUsesConfiguredContentBaseUrl() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        PostMapper postMapper = mock(PostMapper.class);
        RestTemplate contentClient = mock(RestTemplate.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        EsProperties properties = new EsProperties();
        properties.setIndex("rag-test-index");
        PostDetailRow post = publishedPost();
        post.setContentUrl("/uploads/post.md");

        when(postMapper.findDetailById(42L)).thenReturn(post);
        when(elasticsearch.get(any(java.util.function.Function.class), eq(Map.class)))
                .thenThrow(new IOException("force rebuild"));
        doReturn(completeDeleteResponse()).when(elasticsearch)
                .deleteByQuery(any(java.util.function.Function.class));
        when(elasticsearch.indices()).thenReturn(indices);
        doReturn(completeRefreshResponse()).when(indices)
                .refresh(any(java.util.function.Function.class));
        when(contentClient.getForObject(
                "http://content.internal:8888/uploads/post.md", String.class))
                .thenReturn("# Local content\nThe body is readable.");

        RagIndexService service = new RagIndexService(
                vectorStore, postMapper, passThroughLock(), contentClient,
                elasticsearch, properties, "http://content.internal:8888/");

        assertThat(service.reindexSinglePost(42L)).isPositive();
        verify(contentClient).getForObject(
                "http://content.internal:8888/uploads/post.md", String.class);
        verify(vectorStore).add(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void replayCleansPartialBulkBeforePublishingCompletionManifest() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        PostMapper postMapper = mock(PostMapper.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        GetResponse<java.util.Map> missingManifest = mock(GetResponse.class);
        EsProperties properties = new EsProperties();
        properties.setIndex("rag-test-index");
        List<String> operations = new ArrayList<>();
        AtomicBoolean partialChunksPresent = new AtomicBoolean();
        AtomicInteger writeAttempts = new AtomicInteger();

        when(postMapper.findDetailById(42L)).thenReturn(publishedPost());
        when(elasticsearch.get(any(java.util.function.Function.class), eq(java.util.Map.class)))
                .thenReturn(missingManifest);
        when(missingManifest.found()).thenReturn(false);
        when(elasticsearch.indices()).thenReturn(indices);
        when(elasticsearch.delete(any(java.util.function.Function.class)))
                .thenAnswer(invocation -> {
                    operations.add("manifest-delete");
                    return null;
                });
        when(elasticsearch.deleteByQuery(any(java.util.function.Function.class)))
                .thenAnswer(invocation -> {
                    operations.add("chunks-delete");
                    partialChunksPresent.set(false);
                    return completeDeleteResponse();
                });
        doAnswer(invocation -> {
            if (writeAttempts.getAndIncrement() == 0) {
                operations.add("partial-write");
                partialChunksPresent.set(true);
                throw new IllegalStateException("bulk failed after one chunk");
            }
            operations.add("complete-write");
            partialChunksPresent.set(true);
            return null;
        }).when(vectorStore).add(any());
        when(indices.refresh(any(java.util.function.Function.class)))
                .thenAnswer(invocation -> {
                    operations.add("refresh");
                    return completeRefreshResponse();
                });
        when(elasticsearch.index(any(java.util.function.Function.class)))
                .thenAnswer(invocation -> {
                    operations.add("manifest-write");
                    return null;
                });

        RagIndexService service = new RagIndexService(
                vectorStore, postMapper, passThroughLock(), new RestTemplate(),
                elasticsearch, properties);

        assertThatThrownBy(() -> service.reindexSinglePost(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vector index write");
        assertThat(partialChunksPresent).isTrue();
        verify(elasticsearch, never()).index(any(java.util.function.Function.class));

        assertThat(service.reindexSinglePost(42L)).isPositive();

        assertThat(operations).containsExactly(
                "manifest-delete",
                "chunks-delete",
                "partial-write",
                "manifest-delete",
                "chunks-delete",
                "complete-write",
                "refresh",
                "manifest-write");
        assertThat(partialChunksPresent).isTrue();
        verify(vectorStore, org.mockito.Mockito.times(2)).add(any());
        verify(elasticsearch, org.mockito.Mockito.times(2))
                .deleteByQuery(any(java.util.function.Function.class));
        verify(elasticsearch).index(any(java.util.function.Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void matchingCompletionManifestIsTheOnlyFastPath() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        PostMapper postMapper = mock(PostMapper.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        GetResponse<java.util.Map> manifest = mock(GetResponse.class);
        EsProperties properties = new EsProperties();
        properties.setIndex("rag-test-index");
        PostDetailRow post = publishedPost();

        when(postMapper.findDetailById(42L)).thenReturn(post);
        when(elasticsearch.get(any(java.util.function.Function.class), eq(java.util.Map.class)))
                .thenReturn(manifest);
        when(manifest.found()).thenReturn(true);
        when(manifest.source()).thenReturn(java.util.Map.of(
                "documentType", "rag_completion",
                "sourceFingerprint", sourceFingerprint(post),
                "chunkCount", 1,
                "formatVersion", 1));

        RagIndexService service = new RagIndexService(
                vectorStore, postMapper, passThroughLock(), new RestTemplate(),
                elasticsearch, properties);

        assertThat(service.reindexSinglePost(42L)).isZero();

        verify(vectorStore, never()).add(any());
        verify(elasticsearch, never()).delete(any(java.util.function.Function.class));
        verify(elasticsearch, never()).index(any(java.util.function.Function.class));
    }

    @Test
    void completionManifestWriteFailurePropagatesAfterChunksBecomeVisible() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        PostMapper postMapper = mock(PostMapper.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        EsProperties properties = new EsProperties();
        properties.setIndex("rag-test-index");

        when(postMapper.findDetailById(42L)).thenReturn(publishedPost());
        when(elasticsearch.get(any(java.util.function.Function.class), eq(java.util.Map.class)))
                .thenThrow(new IOException("force rebuild"));
        doReturn(completeDeleteResponse()).when(elasticsearch)
                .deleteByQuery(any(java.util.function.Function.class));
        when(elasticsearch.indices()).thenReturn(indices);
        doReturn(completeRefreshResponse()).when(indices)
                .refresh(any(java.util.function.Function.class));
        when(elasticsearch.index(any(java.util.function.Function.class)))
                .thenThrow(new IOException("manifest unavailable"));

        RagIndexService service = new RagIndexService(
                vectorStore, postMapper, passThroughLock(), new RestTemplate(),
                elasticsearch, properties);

        assertThatThrownBy(() -> service.reindexSinglePost(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completion manifest");

        org.mockito.InOrder order = inOrder(vectorStore, indices, elasticsearch);
        order.verify(vectorStore).add(any());
        order.verify(indices).refresh(any(java.util.function.Function.class));
        order.verify(elasticsearch).index(any(java.util.function.Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stalePublicReindexCannotRepublishManifestAfterPrivacyRemoval() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        PostMapper postMapper = mock(PostMapper.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        GetResponse<Map> missingManifest = mock(GetResponse.class);
        EsProperties properties = new EsProperties();
        properties.setIndex("rag-test-index");
        PostDetailRow privatePost = publishedPost();
        privatePost.setVisible("private");
        AtomicBoolean chunksPresent = new AtomicBoolean();
        AtomicBoolean manifestPresent = new AtomicBoolean();
        CountDownLatch staleVectorWriteEntered = new CountDownLatch(1);
        CountDownLatch allowStaleVectorWrite = new CountDownLatch(1);
        CountDownLatch privacyDeleteObserved = new CountDownLatch(1);

        when(postMapper.findDetailById(42L))
                .thenReturn(publishedPost(), privatePost);
        when(elasticsearch.get(any(java.util.function.Function.class), eq(Map.class)))
                .thenReturn(missingManifest);
        when(missingManifest.found()).thenReturn(false);
        when(elasticsearch.indices()).thenReturn(indices);
        doReturn(completeRefreshResponse()).when(indices)
                .refresh(any(java.util.function.Function.class));
        doAnswer(invocation -> {
            staleVectorWriteEntered.countDown();
            if (!allowStaleVectorWrite.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("stale vector write was not released");
            }
            chunksPresent.set(true);
            return null;
        }).when(vectorStore).add(any());
        when(elasticsearch.delete(any(java.util.function.Function.class)))
                .thenAnswer(invocation -> {
                    manifestPresent.set(false);
                    if (Thread.currentThread().getName().startsWith("privacy-rag")) {
                        privacyDeleteObserved.countDown();
                    }
                    return null;
                });
        when(elasticsearch.deleteByQuery(any(java.util.function.Function.class)))
                .thenAnswer(invocation -> {
                    chunksPresent.set(false);
                    return completeDeleteResponse();
                });
        when(elasticsearch.index(any(java.util.function.Function.class)))
                .thenAnswer(invocation -> {
                    manifestPresent.set(true);
                    return null;
                });

        RagIndexService service = new RagIndexService(
                vectorStore, postMapper, serializingLock(), new RestTemplate(),
                elasticsearch, properties);
        ExecutorService staleExecutor = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "stale-rag-writer"));
        ExecutorService privacyExecutor = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "privacy-rag-remover"));
        try {
            Future<Integer> stale = staleExecutor.submit(() -> service.reindexSinglePost(42L));
            assertThat(staleVectorWriteEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> privacy = privacyExecutor.submit(() -> service.reindexSinglePost(42L));

            privacyDeleteObserved.await(1, TimeUnit.SECONDS);
            allowStaleVectorWrite.countDown();

            assertThat(stale.get(5, TimeUnit.SECONDS)).isPositive();
            assertThat(privacy.get(5, TimeUnit.SECONDS)).isZero();
            assertThat(chunksPresent.get()).isFalse();
            assertThat(manifestPresent.get()).isFalse();
        } finally {
            allowStaleVectorWrite.countDown();
            staleExecutor.shutdownNow();
            privacyExecutor.shutdownNow();
        }
    }

    private static String sourceFingerprint(PostDetailRow row) throws Exception {
        java.lang.reflect.Method method = RagIndexService.class.getDeclaredMethod(
                "sourceFingerprint", PostDetailRow.class);
        method.setAccessible(true);
        return (String) method.invoke(null, row);
    }

    private static DeleteByQueryResponse completeDeleteResponse() {
        DeleteByQueryResponse response = mock(DeleteByQueryResponse.class);
        when(response.timedOut()).thenReturn(false);
        when(response.failures()).thenReturn(List.of());
        when(response.versionConflicts()).thenReturn(0L);
        return response;
    }

    private static RefreshResponse completeRefreshResponse() {
        RefreshResponse response = mock(RefreshResponse.class);
        ShardStatistics shards = mock(ShardStatistics.class);
        when(response.shards()).thenReturn(shards);
        when(shards.failed()).thenReturn(0);
        return response;
    }

    @SuppressWarnings("unchecked")
    private void assertIncompleteChunkDeletionFails(
            DeleteByQueryResponse deleteResponse) throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        PostMapper postMapper = mock(PostMapper.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        EsProperties properties = new EsProperties();
        properties.setIndex("rag-test-index");

        when(postMapper.findDetailById(42L)).thenReturn(publishedPost());
        when(elasticsearch.get(any(java.util.function.Function.class), eq(Map.class)))
                .thenThrow(new IOException("force rebuild"));
        when(elasticsearch.deleteByQuery(any(java.util.function.Function.class)))
                .thenReturn(deleteResponse);

        RagIndexService service = new RagIndexService(
                vectorStore, postMapper, passThroughLock(), new RestTemplate(),
                elasticsearch, properties);

        assertThatThrownBy(() -> service.reindexSinglePost(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
        verify(vectorStore, never()).add(any());
        verify(elasticsearch, never()).index(any(java.util.function.Function.class));
    }

    private static RagPostMutationLock passThroughLock() {
        return (postId, mutation) -> mutation.getAsInt();
    }

    private static RagPostMutationLock serializingLock() {
        java.util.concurrent.locks.ReentrantLock lock =
                new java.util.concurrent.locks.ReentrantLock();
        return (postId, mutation) -> {
            lock.lock();
            try {
                return mutation.getAsInt();
            } finally {
                lock.unlock();
            }
        };
    }

    private PostDetailRow publishedPost() {
        PostDetailRow row = new PostDetailRow();
        row.setId(42L);
        row.setTitle("可见性测试");
        row.setStatus("published");
        row.setVisible("public");
        row.setContentSha256("a".repeat(64));
        row.setContentEtag("etag-42");
        row.setContentUrl("http://127.0.0.1:" + contentServer.getAddress().getPort() + "/post.md");
        return row;
    }
}
