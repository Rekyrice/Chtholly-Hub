package com.chtholly.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.util.ObjectBuilder;
import com.chtholly.config.EsProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchIndexInitializerTest {

    @Test
    void existingIndexReceivesAdditiveAuthorHandleMappingBeforeBackfill() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        BooleanResponse exists = mock(BooleanResponse.class);
        SearchIndexService indexService = mock(SearchIndexService.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(java.util.function.Function.class)))
                .thenReturn(exists);
        when(exists.value()).thenReturn(true);

        new SearchIndexInitializer(client, indexService, new EsProperties()).ensureIndex();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<PutMappingRequest.Builder, ObjectBuilder<PutMappingRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);
        verify(indices).putMapping(captor.capture());
        PutMappingRequest request = captor.getValue().apply(new PutMappingRequest.Builder()).build();
        assertThat(request.properties()).containsKeys("author_handle", "visible");
        verify(indexService).ensureBackfill();
    }
}
