package com.chtholly.llm.rag;

import com.chtholly.agent.CharacterSoulService;
import com.chtholly.agent.evidence.EvidenceSet;
import com.chtholly.agent.search.SearchResult;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagQueryServiceTest {

    @Test
    void semanticSearchPreservesOnlyMysqlAuthorizedCurrentChunkMetadata() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(
                        document("42", "42#3", "current-sha", "current chunk"),
                        document("43", "43#0", "old-sha", "stale chunk"),
                        new Document("missing identity", Map.of("title", "invalid"))));
        PostMapper postMapper = mock(PostMapper.class);
        when(postMapper.findByIds(List.of(42L, 43L))).thenReturn(List.of(
                publicPost(42L, "current-sha"),
                publicPost(43L, "new-sha")));
        RagQueryService service = service(vectorStore, mock(ChatClient.class), mock(RagIndexService.class), postMapper);

        List<SearchResult> results = service.search("time", 5);

        assertThat(results).hasSize(1);
        SearchResult result = results.getFirst();
        assertThat(result.getId()).isEqualTo("post:42");
        assertThat(result.getDocumentId()).isEqualTo("post:42");
        assertThat(result.getChunkId()).isEqualTo("42#3");
        assertThat(result.getSourceHash()).isEqualTo("current-sha");
        assertThat(result.getPermissions()).containsExactly("PUBLIC");
    }

    @Test
    void privatePostQuestionReturnsNoAnswerWithoutRetrievalOrModelCall() {
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient chatClient = mock(ChatClient.class);
        RagIndexService indexService = mock(RagIndexService.class);
        PostMapper postMapper = mock(PostMapper.class);
        when(postMapper.findById(42L)).thenReturn(Post.builder()
                .id(42L)
                .status("published")
                .visible("private")
                .contentSha256("sha")
                .build());
        RagQueryService service = service(vectorStore, chatClient, indexService, postMapper);

        assertThat(service.streamAnswerFlux(42L, "secret", 3, 100).collectList().block())
                .containsExactly(EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER);
        verify(indexService).ensureIndexed(42L);
        verify(vectorStore, never()).similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class));
        verifyNoInteractions(chatClient);
    }

    @Test
    void semanticSearchUsesMatchingEtagWhenShaIsUnavailable() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(new Document("etag chunk", Map.of(
                        "postId", "42",
                        "chunkId", "42#0",
                        "contentEtag", "etag-42"))));
        PostMapper postMapper = mock(PostMapper.class);
        when(postMapper.findByIds(List.of(42L))).thenReturn(List.of(Post.builder()
                .id(42L)
                .title("Post 42")
                .status("published")
                .visible("public")
                .contentEtag("etag-42")
                .build()));
        RagQueryService service = service(vectorStore, mock(ChatClient.class), mock(RagIndexService.class), postMapper);

        assertThat(service.search("etag", 3))
                .extracting(SearchResult::getSourceHash)
                .containsExactly("etag-42");
    }

    @Test
    void semanticSearchRejectsPostWithoutCurrentFingerprint() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(document("42", "42#0", "indexed-sha", "chunk")));
        PostMapper postMapper = mock(PostMapper.class);
        when(postMapper.findByIds(List.of(42L))).thenReturn(List.of(Post.builder()
                .id(42L)
                .status("published")
                .visible("public")
                .build()));
        RagQueryService service = service(vectorStore, mock(ChatClient.class), mock(RagIndexService.class), postMapper);

        assertThat(service.search("missing fingerprint", 3)).isEmpty();
    }

    @Test
    void semanticSearchRejectsBlankCurrentExcerpt() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(document("42", "42#0", "current-sha", "   ")));
        PostMapper postMapper = mock(PostMapper.class);
        when(postMapper.findByIds(List.of(42L))).thenReturn(List.of(publicPost(42L, "current-sha")));
        RagQueryService service = service(
                vectorStore, mock(ChatClient.class), mock(RagIndexService.class), postMapper);

        assertThat(service.search("blank", 3)).isEmpty();
    }

    @Test
    void authorityLookupFailureNeverReturnsUnverifiedChunks() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(document("42", "42#0", "current-sha", "chunk")));
        PostMapper postMapper = mock(PostMapper.class);
        when(postMapper.findByIds(List.of(42L)))
                .thenThrow(new IllegalStateException("mysql unavailable"));
        RagQueryService service = service(
                vectorStore, mock(ChatClient.class), mock(RagIndexService.class), postMapper);

        assertThatThrownBy(() -> service.search("authority", 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql unavailable");
    }

    @Test
    void stalePostChunksReturnNoAnswerWithoutModelCall() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(document("42", "42#0", "old-sha", "stale")));
        ChatClient chatClient = mock(ChatClient.class);
        PostMapper postMapper = mock(PostMapper.class);
        when(postMapper.findById(42L)).thenReturn(publicPost(42L, "new-sha"));
        RagQueryService service = service(vectorStore, chatClient, mock(RagIndexService.class), postMapper);

        assertThat(service.streamAnswerFlux(42L, "question", 3, 100).collectList().block())
                .containsExactly(EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER);
        verifyNoInteractions(chatClient);
    }

    @Test
    void indexFailureReturnsNoAnswerWithoutRetrievalOrModelCall() {
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient chatClient = mock(ChatClient.class);
        RagIndexService indexService = mock(RagIndexService.class);
        doThrow(new IllegalStateException("index down")).when(indexService).ensureIndexed(42L);
        RagQueryService service = service(vectorStore, chatClient, indexService, mock(PostMapper.class));

        assertThat(service.streamAnswerFlux(42L, "question", 3, 100).collectList().block())
                .containsExactly(EvidenceSet.INSUFFICIENT_EVIDENCE_ANSWER);
        verifyNoInteractions(vectorStore, chatClient);
    }

    private RagQueryService service(
            VectorStore vectorStore, ChatClient chatClient, RagIndexService indexService, PostMapper postMapper) {
        return new RagQueryService(
                vectorStore,
                chatClient,
                indexService,
                mock(CharacterSoulService.class),
                postMapper);
    }

    private Document document(String postId, String chunkId, String hash, String text) {
        return new Document(text, Map.of(
                "postId", postId,
                "chunkId", chunkId,
                "title", "Post " + postId,
                "contentSha256", hash));
    }

    private Post publicPost(long id, String hash) {
        return Post.builder()
                .id(id)
                .title("Post " + id)
                .status("published")
                .visible("public")
                .contentSha256(hash)
                .build();
    }
}
