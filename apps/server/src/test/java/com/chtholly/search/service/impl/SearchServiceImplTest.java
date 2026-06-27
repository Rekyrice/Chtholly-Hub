package com.chtholly.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.chtholly.counter.service.CounterService;
import com.chtholly.search.api.dto.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock
    private ElasticsearchClient es;
    @Mock
    private CounterService counterService;

    private SearchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SearchServiceImpl(es, counterService);
    }

    @Test
    void given_esHits_when_search_then_mapsFeedItemsCorrectly() throws Exception {
        Map<String, Object> source = new HashMap<>();
        source.put("content_id", 42L);
        source.put("title", "Re:Zero");
        source.put("slug", "re-zero");
        source.put("description", "desc");
        source.put("tags", List.of("anime"));
        source.put("like_count", 10L);
        source.put("favorite_count", 3L);
        source.put("author_nickname", "tester");

        @SuppressWarnings("unchecked")
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> esResp = mock(
                co.elastic.clients.elasticsearch.core.SearchResponse.class);
        HitsMetadata<Map<String, Object>> hitsMeta = mock(HitsMetadata.class);
        Hit<Map<String, Object>> hit = mock(Hit.class);
        when(hit.source()).thenReturn(source);
        when(hit.highlight()).thenReturn(null);
        when(hit.sort()).thenReturn(List.of(
                FieldValue.of(2.5),
                FieldValue.of(1700000000L),
                FieldValue.of(10L),
                FieldValue.of(20L),
                FieldValue.of(42L)
        ));
        when(hitsMeta.hits()).thenReturn(List.of(hit));
        when(esResp.hits()).thenReturn(hitsMeta);
        when(es.search(any(Function.class), any(Class.class))).thenReturn(esResp);

        SearchResponse response = service.search("re0", 10, null, null, null);

        assertThat(response.degraded()).isFalse();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).id()).isEqualTo("42");
        assertThat(response.items().get(0).title()).isEqualTo("Re:Zero");
        assertThat(response.items().get(0).slug()).isEqualTo("re-zero");
        assertThat(response.hasMore()).isFalse();
    }

    @Test
    void given_esThrows_when_search_then_returnsDegradedResponse() throws Exception {
        when(es.search(any(Function.class), any(Class.class))).thenThrow(new RuntimeException("ES down"));

        SearchResponse response = service.search("fail", 10, null, null, null);

        assertThat(response.degraded()).isTrue();
        assertThat(response.items()).isEmpty();
        assertThat(response.nextAfter()).isNull();
        assertThat(response.hasMore()).isFalse();
    }

    @Test
    void given_sortValues_when_search_then_cursorEncodesAndDecodesConsistently() throws Exception {
        Map<String, Object> source = Map.of(
                "content_id", 99L,
                "title", "Cursor Test",
                "slug", "cursor-test"
        );

        @SuppressWarnings("unchecked")
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> esResp = mock(
                co.elastic.clients.elasticsearch.core.SearchResponse.class);
        HitsMetadata<Map<String, Object>> hitsMeta = mock(HitsMetadata.class);
        Hit<Map<String, Object>> hit = mock(Hit.class);
        when(hit.source()).thenReturn(source);
        when(hit.highlight()).thenReturn(null);
        when(hit.sort()).thenReturn(List.of(
                FieldValue.of(1.25),
                FieldValue.of(1234567890L),
                FieldValue.of(5L),
                FieldValue.of(8L),
                FieldValue.of(99L)
        ));
        when(hitsMeta.hits()).thenReturn(List.of(hit));
        when(esResp.hits()).thenReturn(hitsMeta);
        when(es.search(any(Function.class), any(Class.class))).thenReturn(esResp);

        SearchResponse first = service.search("cursor", 1, null, null, null);

        assertThat(first.nextAfter()).isNotNull();
        String decoded = new String(Base64.getUrlDecoder().decode(first.nextAfter()));
        assertThat(decoded).isEqualTo("1.25,1234567890,5,8,99");

        SearchResponse second = service.search("cursor", 1, null, first.nextAfter(), null);

        assertThat(second.degraded()).isFalse();
        verify(es, times(2)).search(any(Function.class), any(Class.class));
    }
}
