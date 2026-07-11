package com.chtholly.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostDetailRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class SearchIndexServiceTest {

    @Test
    void given_relativeContentUrl_when_tryUpsert_then_fetchesAbsoluteLocalUrl() throws Exception {
        ElasticsearchClient es = mock(ElasticsearchClient.class);
        PostMapper posts = mock(PostMapper.class);
        CounterService counters = mock(CounterService.class);
        RestTemplate rest = mock(RestTemplate.class);
        PostDetailRow row = row(7L, "/uploads/post.md");
        when(posts.findDetailById(7L)).thenReturn(row);
        when(counters.getCounts(any(), any(), any())).thenReturn(Map.of());
        when(rest.exchange(eq("http://localhost:8888/uploads/post.md"), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>("完整正文".getBytes(StandardCharsets.UTF_8), HttpStatus.OK));
        when(es.index(any(co.elastic.clients.elasticsearch.core.IndexRequest.class)))
                .thenReturn(mock(IndexResponse.class));
        SearchIndexService service = new SearchIndexService(
                es, posts, counters, new ObjectMapper(), rest, "http://localhost:8888/");

        assertThat(service.tryUpsertPost(7L)).isTrue();

        verify(rest).exchange(eq("http://localhost:8888/uploads/post.md"), eq(HttpMethod.GET), any(), eq(byte[].class));
        ArgumentCaptor<co.elastic.clients.elasticsearch.core.IndexRequest<Map<String, Object>>> request =
                ArgumentCaptor.forClass(co.elastic.clients.elasticsearch.core.IndexRequest.class);
        verify(es).index(request.capture());
        assertThat(request.getValue().document()).containsEntry("body", "完整正文");
    }

    @Test
    void given_indexException_when_tryUpsert_then_returnsFalse() throws Exception {
        ElasticsearchClient es = mock(ElasticsearchClient.class);
        PostMapper posts = mock(PostMapper.class);
        CounterService counters = mock(CounterService.class);
        PostDetailRow row = row(8L, "https://cdn.example/post.md");
        when(posts.findDetailById(8L)).thenReturn(row);
        when(counters.getCounts(any(), any(), any())).thenReturn(Map.of());
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.exchange(any(String.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>("body".getBytes(StandardCharsets.UTF_8), HttpStatus.OK));
        when(es.index(any(co.elastic.clients.elasticsearch.core.IndexRequest.class)))
                .thenThrow(new IllegalStateException("ES unavailable"));
        SearchIndexService service = new SearchIndexService(
                es, posts, counters, new ObjectMapper(), rest, "http://localhost:8888");

        assertThat(service.tryUpsertPost(8L)).isFalse();
    }

    @Test
    void given_relativeContentFetchFailure_when_strictUpsertRetried_then_skipsEsUntilFullBodyRecovers() throws Exception {
        ElasticsearchClient es = mock(ElasticsearchClient.class);
        PostMapper posts = mock(PostMapper.class);
        CounterService counters = mock(CounterService.class);
        RestTemplate rest = mock(RestTemplate.class);
        when(posts.findDetailById(9L)).thenReturn(row(9L, "/uploads/retry.md"));
        when(counters.getCounts(any(), any(), any())).thenReturn(Map.of());
        when(rest.exchange(eq("http://localhost:8888/uploads/retry.md"), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenThrow(new IllegalStateException("timeout"))
                .thenReturn(new ResponseEntity<>("恢复后的完整正文".getBytes(StandardCharsets.UTF_8), HttpStatus.OK));
        when(es.index(any(co.elastic.clients.elasticsearch.core.IndexRequest.class)))
                .thenReturn(mock(IndexResponse.class));
        SearchIndexService service = new SearchIndexService(
                es, posts, counters, new ObjectMapper(), rest, "http://localhost:8888");

        assertThat(service.tryUpsertPost(9L)).isFalse();
        verify(es, never()).index(any(co.elastic.clients.elasticsearch.core.IndexRequest.class));

        assertThat(service.tryUpsertPost(9L)).isTrue();
        ArgumentCaptor<co.elastic.clients.elasticsearch.core.IndexRequest<Map<String, Object>>> request =
                ArgumentCaptor.forClass(co.elastic.clients.elasticsearch.core.IndexRequest.class);
        verify(es).index(request.capture());
        assertThat(request.getValue().document()).containsEntry("body", "恢复后的完整正文");
    }

    @Test
    void given_malformedDeclaredUtf8_when_strictUpsert_then_reportsFailureWithoutWritingEs() throws Exception {
        ElasticsearchClient es = mock(ElasticsearchClient.class);
        PostMapper posts = mock(PostMapper.class);
        CounterService counters = mock(CounterService.class);
        RestTemplate rest = mock(RestTemplate.class);
        when(posts.findDetailById(10L)).thenReturn(row(10L, "/uploads/broken.md"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        when(rest.exchange(any(String.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(new byte[]{(byte) 0xc3, 0x28}, headers, HttpStatus.OK));
        SearchIndexService service = new SearchIndexService(
                es, posts, counters, new ObjectMapper(), rest, "http://localhost:8888");

        assertThat(service.tryUpsertPost(10L)).isFalse();
        verify(es, never()).index(any(co.elastic.clients.elasticsearch.core.IndexRequest.class));
    }

    private PostDetailRow row(long id, String contentUrl) {
        PostDetailRow row = new PostDetailRow();
        row.setId(id);
        row.setType("post");
        row.setSlug("post-" + id);
        row.setTitle("title");
        row.setDescription("description");
        row.setStatus("published");
        row.setContentUrl(contentUrl);
        return row;
    }
}
