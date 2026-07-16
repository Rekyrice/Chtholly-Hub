package com.chtholly.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.chtholly.config.EsProperties;
import org.junit.jupiter.api.Test;

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

        verify(indices).putMapping(any(java.util.function.Function.class));
        verify(indexService).ensureBackfill();
    }
}
