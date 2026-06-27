package com.chtholly.agent.tools;

import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleRagToolTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private PostMapper postMapper;

    private ArticleRagTool tool;

    @BeforeEach
    void setUp() {
        tool = new ArticleRagTool(vectorStore, postMapper);
    }

    @Test
    void given_topKDocs_when_execute_then_batchLoadsPostsOnce() {
        List<Document> docs = List.of(
                new Document("chunk-1", Map.of("postId", 1L, "title", "t1")),
                new Document("chunk-2", Map.of("postId", 2L, "title", "t2")),
                new Document("chunk-3", Map.of("postId", 1L, "title", "t1-dup"))
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(docs);

        Post p1 = new Post();
        p1.setId(1L);
        p1.setTitle("Post One");
        p1.setSlug("post-one");
        Post p2 = new Post();
        p2.setId(2L);
        p2.setTitle("Post Two");
        p2.setSlug("post-two");
        when(postMapper.findByIds(any())).thenReturn(List.of(p1, p2));

        String result = tool.execute(Map.of("query", "test", "topK", 10), 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(postMapper, times(1)).findByIds(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L);
        verify(postMapper, times(0)).findById(any());
        assertThat(result).contains("Post One").contains("Post Two").contains("/post/post-one");
    }

    @Test
    void given_noDocs_when_execute_then_noDbQuery() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        String result = tool.execute(Map.of("query", "empty"), 1L);

        verify(postMapper, times(0)).findByIds(any());
        assertThat(result).contains("未找到");
    }
}
