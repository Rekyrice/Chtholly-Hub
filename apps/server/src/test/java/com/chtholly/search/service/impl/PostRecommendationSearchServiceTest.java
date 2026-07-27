package com.chtholly.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.util.ObjectBuilder;
import com.chtholly.post.api.dto.FeedItemResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostRecommendationSearchServiceTest {

    @Test
    void emptyInterestProfileFallsBackToHotRecommendations() throws Exception {
        ElasticsearchClient es = mock(ElasticsearchClient.class);
        SearchHitMapper mapper = mock(SearchHitMapper.class);
        @SuppressWarnings("unchecked")
        SearchResponse<Map<String, Object>> response = mock(SearchResponse.class);
        @SuppressWarnings("unchecked")
        HitsMetadata<Map<String, Object>> hits = mock(HitsMetadata.class);
        when(response.hits()).thenReturn(hits);
        when(hits.hits()).thenReturn(List.of());
        when(es.search(any(java.util.function.Function.class), any(Class.class))).thenReturn(response);
        when(mapper.mapPostHits(List.of(), null)).thenReturn(List.of());
        PostRecommendationSearchService service =
                new PostRecommendationSearchService(es, mapper);

        List<FeedItemResponse> result = service.recommendByInterest(Map.of(), List.of(), 5, null);

        assertThat(result).isEmpty();
        verify(es).search(any(java.util.function.Function.class), any(Class.class));
    }

    @Test
    void hotRecommendationReturnsEmptyWhenElasticsearchFails() throws Exception {
        ElasticsearchClient es = mock(ElasticsearchClient.class);
        SearchHitMapper mapper = mock(SearchHitMapper.class);
        when(es.search(any(java.util.function.Function.class), any(Class.class)))
                .thenThrow(new RuntimeException("ES down"));
        PostRecommendationSearchService service =
                new PostRecommendationSearchService(es, mapper);

        assertThat(service.recommendHot(List.of(), 5, null)).isEmpty();
    }

    @Test
    void hotRecommendationFiltersPublishedAndPublicPosts() throws Exception {
        ElasticsearchClient es = mock(ElasticsearchClient.class);
        SearchHitMapper mapper = mock(SearchHitMapper.class);
        @SuppressWarnings("unchecked")
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> response =
                mock(co.elastic.clients.elasticsearch.core.SearchResponse.class);
        @SuppressWarnings("unchecked")
        HitsMetadata<Map<String, Object>> hits = mock(HitsMetadata.class);
        when(response.hits()).thenReturn(hits);
        when(hits.hits()).thenReturn(List.of());
        when(es.search(any(Function.class), any(Class.class))).thenReturn(response);
        when(mapper.mapPostHits(List.of(), null)).thenReturn(List.of());
        PostRecommendationSearchService service = new PostRecommendationSearchService(es, mapper);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);

        service.recommendHot(List.of(), 5, null);

        verify(es).search(captor.capture(), any(Class.class));
        SearchRequest request = captor.getValue().apply(new SearchRequest.Builder()).build();
        assertThat(request.query().bool().filter()).anySatisfy(filter -> {
            assertThat(filter.term().field()).isEqualTo("visible");
            assertThat(filter.term().value().stringValue()).isEqualTo("public");
        });
    }
}
