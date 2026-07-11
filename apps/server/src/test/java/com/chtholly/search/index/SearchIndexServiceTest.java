package com.chtholly.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.mapper.PostMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchIndexServiceTest {

    @Test
    void softDeletePropagatesElasticsearchFailureSoOutboxCanRetry() throws Exception {
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(elasticsearch.index(any(co.elastic.clients.elasticsearch.core.IndexRequest.class)))
                .thenThrow(new IOException("elasticsearch unavailable"));
        SearchIndexService service = new SearchIndexService(
                elasticsearch,
                mock(PostMapper.class),
                mock(CounterService.class),
                new ObjectMapper(),
                mock(RestTemplate.class));

        assertThatThrownBy(() -> service.softDeletePost(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99")
                .hasCauseInstanceOf(IOException.class);
    }
}
