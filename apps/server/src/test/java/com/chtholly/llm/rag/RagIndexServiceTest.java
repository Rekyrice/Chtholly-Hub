package com.chtholly.llm.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import com.chtholly.config.EsProperties;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostDetailRow;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        when(elasticsearch.search(any(java.util.function.Function.class), eq(java.util.Map.class)))
                .thenThrow(new IOException("index unavailable"));
        when(elasticsearch.indices()).thenReturn(indices);
        when(indices.refresh(any(java.util.function.Function.class)))
                .thenThrow(new IOException("refresh unavailable"));

        RagIndexService service = new RagIndexService(vectorStore, postMapper, elasticsearch, properties);

        assertThatThrownBy(() -> service.reindexSinglePost(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refresh");
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
