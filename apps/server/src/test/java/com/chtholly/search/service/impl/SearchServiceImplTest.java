package com.chtholly.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.MsearchRequest;
import co.elastic.clients.elasticsearch.core.MsearchResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchItem;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.util.ObjectBuilder;
import com.chtholly.counter.service.CounterService;
import com.chtholly.comment.service.CommentService;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.search.api.dto.HubFeedResponse;
import com.chtholly.search.service.SearchSort;
import com.chtholly.user.service.PublicAuthorQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock
    private ElasticsearchClient es;
    @Mock
    private CounterService counterService;
    @Mock
    private CommentService commentService;
    @Mock
    private PublicAuthorQueryService publicAuthorQueryService;

    private SearchServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(publicAuthorQueryService.findByIds(any())).thenReturn(Map.of());
        lenient().when(commentService.countActiveByPostIds(any())).thenReturn(Map.of());
        SearchHitMapper hitMapper = new SearchHitMapper(counterService, publicAuthorQueryService, commentService);
        service = new SearchServiceImpl(
                es,
                hitMapper,
                new HubFeedSearchService(es, hitMapper),
                new PostRecommendationSearchService(es, hitMapper));
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

        PageResponse<FeedItemResponse> response = service.search(
                "re0", 10, null, null, SearchSort.RELEVANCE, null);

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

        PageResponse<FeedItemResponse> response = service.search(
                "fail", 10, null, null, SearchSort.RELEVANCE, null);

        assertThat(response.degraded()).isTrue();
        assertThat(response.items()).isEmpty();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasMore()).isFalse();
    }

    @Test
    void givenEntityNames_whenSearchByEntityNames_thenUsesOneNestedArticleQuery() throws Exception {
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> empty = emptySearchResponse();
        when(es.search(any(Function.class), any(Class.class))).thenReturn(empty);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);

        PageResponse<FeedItemResponse> response = service.searchByEntityNames(
                List.of("芙莉莲", "辛美尔"), 5, null);

        verify(es).search(captor.capture(), any(Class.class));
        SearchRequest request = captor.getValue().apply(new SearchRequest.Builder()).build();
        assertThat(response.degraded()).isFalse();
        assertThat(request.size()).isEqualTo(5);
        assertThat(request.query().bool().must().getFirst().nested().path())
                .isEqualTo("contentAnalysis.entities");
        assertThat(request.query().bool().must().getFirst().nested().query().terms().field())
                .isEqualTo("contentAnalysis.entities.name");
        assertThat(request.query().bool().must().getFirst().nested().query().terms().terms().value())
                .extracting(FieldValue::stringValue)
                .containsExactly("芙莉莲", "辛美尔");
        assertThat(request.query().bool().filter().getFirst().term().field()).isEqualTo("status");
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

        PageResponse<FeedItemResponse> first = service.search(
                "cursor", 1, null, null, SearchSort.RELEVANCE, null);

        assertThat(first.nextCursor()).isNotNull();
        String decoded = new String(Base64.getUrlDecoder().decode(first.nextCursor()));
        assertThat(decoded).isEqualTo("1.25,1234567890,5,8,99");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);

        PageResponse<FeedItemResponse> second = service.search(
                "cursor", 1, null, first.nextCursor(), SearchSort.RELEVANCE, null);

        assertThat(second.degraded()).isFalse();
        verify(es, times(2)).search(captor.capture(), any(Class.class));
        SearchRequest secondRequest = captor.getAllValues().get(1).apply(new SearchRequest.Builder()).build();
        assertThat(secondRequest.searchAfter()).hasSize(5);
        assertThat(secondRequest.searchAfter().getFirst().doubleValue()).isEqualTo(1.25);
        assertThat(secondRequest.searchAfter().get(4).longValue()).isEqualTo(99L);
    }

    @Test
    void given_newestSort_when_search_then_usesPublishTimeAndContentIdDescending() throws Exception {
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> response = emptySearchResponse();
        when(es.search(any(Function.class), any(Class.class))).thenReturn(response);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);

        service.search("latest", 10, null, null, SearchSort.NEWEST, null);

        verify(es).search(captor.capture(), any(Class.class));
        SearchRequest request = captor.getValue().apply(new SearchRequest.Builder()).build();
        assertThat(request.sort()).hasSize(2);
        assertThat(request.sort().get(0).field().field()).isEqualTo("publish_time");
        assertThat(request.sort().get(0).field().order()).isEqualTo(co.elastic.clients.elasticsearch._types.SortOrder.Desc);
        assertThat(request.sort().get(1).field().field()).isEqualTo("content_id");
        assertThat(request.sort().get(1).field().order()).isEqualTo(co.elastic.clients.elasticsearch._types.SortOrder.Desc);
    }

    @Test
    void given_relevanceOrNullSort_when_search_then_usesStableFiveFieldOrder() throws Exception {
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> response = emptySearchResponse();
        when(es.search(any(Function.class), any(Class.class))).thenReturn(response);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);

        service.search("ranked", 10, null, null, SearchSort.RELEVANCE, null);
        service.search("ranked", 10, null, null, null, null);

        verify(es, times(2)).search(captor.capture(), any(Class.class));
        for (Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> requestFactory : captor.getAllValues()) {
            SearchRequest request = requestFactory.apply(new SearchRequest.Builder()).build();
            assertThat(request.sort()).hasSize(5);
            assertThat(request.sort().getFirst().isScore()).isTrue();
            assertThat(request.sort().getFirst().score().order())
                    .isEqualTo(co.elastic.clients.elasticsearch._types.SortOrder.Desc);
            assertThat(request.sort().subList(1, 5))
                    .extracting(sort -> sort.field().field())
                    .containsExactly("publish_time", "like_count", "view_count", "content_id");
            assertThat(request.sort().subList(1, 5))
                    .allSatisfy(sort -> assertThat(sort.field().order())
                            .isEqualTo(co.elastic.clients.elasticsearch._types.SortOrder.Desc));
        }
    }

    @Test
    void given_newestSortValues_when_searchThenContinue_then_roundTripsTwoLongSearchAfterValues() throws Exception {
        Map<String, Object> source = Map.of(
                "content_id", 99L,
                "title", "Newest Cursor",
                "slug", "newest-cursor"
        );
        @SuppressWarnings("unchecked")
        Hit<Map<String, Object>> hit = mock(Hit.class);
        when(hit.source()).thenReturn(source);
        when(hit.highlight()).thenReturn(null);
        when(hit.sort()).thenReturn(List.of(FieldValue.of(1700000000L), FieldValue.of(99L)));
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> response =
                searchResponse(List.of(hit));
        when(es.search(any(Function.class), any(Class.class))).thenReturn(response);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);

        PageResponse<FeedItemResponse> first = service.search(
                "latest", 1, null, null, SearchSort.NEWEST, null);
        PageResponse<FeedItemResponse> second = service.search(
                "latest", 1, null, first.nextCursor(), SearchSort.NEWEST, null);

        assertThat(new String(Base64.getUrlDecoder().decode(first.nextCursor())))
                .isEqualTo("1700000000,99");
        assertThat(second.degraded()).isFalse();
        verify(es, times(2)).search(captor.capture(), any(Class.class));
        SearchRequest secondRequest = captor.getAllValues().get(1).apply(new SearchRequest.Builder()).build();
        assertThat(secondRequest.searchAfter()).hasSize(2);
        assertThat(secondRequest.searchAfter()).allSatisfy(value -> assertThat(value.isLong()).isTrue());
        assertThat(secondRequest.searchAfter().getFirst().longValue()).isEqualTo(1700000000L);
        assertThat(secondRequest.searchAfter().get(1).longValue()).isEqualTo(99L);
    }

    @Test
    void given_cursorFromDifferentSort_when_search_then_ignoresItDeterministically() throws Exception {
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> response = emptySearchResponse();
        when(es.search(any(Function.class), any(Class.class))).thenReturn(response);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);
        String newestCursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1700000000,99".getBytes());
        String relevanceCursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1.25,1700000000,5,8,99".getBytes());

        service.search("ranked", 10, null, newestCursor, SearchSort.RELEVANCE, null);
        service.search("latest", 10, null, relevanceCursor, SearchSort.NEWEST, null);

        verify(es, times(2)).search(captor.capture(), any(Class.class));
        assertThat(captor.getAllValues())
                .allSatisfy(factory -> assertThat(
                        factory.apply(new SearchRequest.Builder()).build().searchAfter()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "1e309"})
    void given_nonFiniteRelevanceScoreCursor_when_search_then_ignoresCursorAndReturnsFirstPage(
            String score) throws Exception {
        assertInvalidCursorIgnored(
                score + ",1700000000,5,8,99",
                SearchSort.RELEVANCE);
    }

    @Test
    void given_negativeRelevanceScoreCursor_when_search_then_ignoresCursorAndReturnsFirstPage() throws Exception {
        assertInvalidCursorIgnored(
                "-0.01,1700000000,5,8,99",
                SearchSort.RELEVANCE);
    }

    @ParameterizedTest
    @MethodSource("negativeLongSortCursors")
    void given_negativeLongSortValue_when_search_then_ignoresCursorAndReturnsFirstPage(
            SearchSort sort, String decodedCursor) throws Exception {
        assertInvalidCursorIgnored(decodedCursor, sort);
    }

    @ParameterizedTest
    @MethodSource("malformedSortCursors")
    void given_malformedSortValue_when_search_then_ignoresCursorAndReturnsFirstPage(
            SearchSort sort, String decodedCursor) throws Exception {
        assertInvalidCursorIgnored(decodedCursor, sort);
    }

    @Test
    void given_msearchItems_when_hubFeed_then_mapsEveryRegionAndUsesSingleRequest() throws Exception {
        MultiSearchResponseItem<Map<String, Object>> latest =
                resultItem(List.of(postSource(42L, "Latest", "latest")), Collections.emptyMap());
        MultiSearchResponseItem<Map<String, Object>> tags =
                resultItem(List.of(), Map.of("hot_tags", hotTagsAggregate()));
        MultiSearchResponseItem<Map<String, Object>> recommendations =
                resultItem(List.of(postSource(77L, "Recommended", "recommended")), Collections.emptyMap());
        MultiSearchResponseItem<Map<String, Object>> experiences =
                resultItem(List.of(experienceSource("今天仓库里也很安静呢")), Collections.emptyMap());
        MsearchResponse<Map<String, Object>> msearch =
                msearchResponse(latest, tags, recommendations, experiences);
        when(es.msearch(any(Function.class), eq(Map.class)))
                .thenReturn(msearch);

        HubFeedResponse response = service.hubFeed("anime,治愈", null, 1, 8);

        assertThat(response.latestPostsStatus()).isEqualTo("ok");
        assertThat(response.latestPosts()).extracting(FeedItemResponse::title).containsExactly("Latest");
        assertThat(response.latestPostsTotal()).isEqualTo(1);
        assertThat(response.hotTagsStatus()).isEqualTo("ok");
        assertThat(response.hotTags()).extracting("name").containsExactly("anime");
        assertThat(response.recommendationsStatus()).isEqualTo("ok");
        assertThat(response.recommendations()).extracting(FeedItemResponse::title).containsExactly("Recommended");
        assertThat(response.experiencesStatus()).isEqualTo("ok");
        assertThat(response.experiences()).extracting("text").containsExactly("今天仓库里也很安静呢");
        verify(es).msearch(any(Function.class), eq(Map.class));
    }

    @Test
    void given_pageAndSize_when_hubFeed_then_latestPostsRequestUsesFromSizeAndReturnsTotal() throws Exception {
        MultiSearchResponseItem<Map<String, Object>> latest =
                resultItem(List.of(postSource(42L, "Latest", "latest")), Collections.emptyMap(), 23);
        MsearchResponse<Map<String, Object>> msearch =
                msearchResponse(latest, resultItem(List.of(), Collections.emptyMap()),
                        resultItem(List.of(), Collections.emptyMap()), resultItem(List.of(), Collections.emptyMap()));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<MsearchRequest.Builder, ObjectBuilder<MsearchRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);
        when(es.msearch(any(Function.class), eq(Map.class))).thenReturn(msearch);

        HubFeedResponse response = service.hubFeed(null, null, 2, 8);

        verify(es).msearch(captor.capture(), eq(Map.class));
        MsearchRequest request = captor.getValue().apply(new MsearchRequest.Builder()).build();
        assertThat(request.searches().getFirst().body().from()).isEqualTo(8);
        assertThat(request.searches().getFirst().body().size()).isEqualTo(8);
        assertThat(response.latestPostsTotal()).isEqualTo(23);
    }

    @Test
    void given_oneMsearchItemFails_when_hubFeed_then_onlyThatRegionIsDegraded() throws Exception {
        MultiSearchResponseItem<Map<String, Object>> latest =
                resultItem(List.of(postSource(42L, "Latest", "latest")), Collections.emptyMap());
        MultiSearchResponseItem<Map<String, Object>> failedTags = mock(MultiSearchResponseItem.class);
        when(failedTags.isFailure()).thenReturn(true);
        MultiSearchResponseItem<Map<String, Object>> recommendations =
                resultItem(List.of(postSource(77L, "Recommended", "recommended")), Collections.emptyMap());
        MultiSearchResponseItem<Map<String, Object>> experiences =
                resultItem(List.of(experienceSource("安静的一天")), Collections.emptyMap());
        MsearchResponse<Map<String, Object>> msearch =
                msearchResponse(latest, failedTags, recommendations, experiences);
        when(es.msearch(any(Function.class), eq(Map.class)))
                .thenReturn(msearch);

        HubFeedResponse response = service.hubFeed(null, null, 1, 8);

        assertThat(response.latestPostsStatus()).isEqualTo("ok");
        assertThat(response.latestPosts()).hasSize(1);
        assertThat(response.hotTagsStatus()).isEqualTo("degraded");
        assertThat(response.hotTags()).isEmpty();
        assertThat(response.recommendationsStatus()).isEqualTo("ok");
        assertThat(response.experiencesStatus()).isEqualTo("ok");
    }

    @SafeVarargs
    private MsearchResponse<Map<String, Object>> msearchResponse(MultiSearchResponseItem<Map<String, Object>>... items) {
        @SuppressWarnings("unchecked")
        MsearchResponse<Map<String, Object>> response = mock(MsearchResponse.class);
        when(response.responses()).thenReturn(List.of(items));
        return response;
    }

    private co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> emptySearchResponse() {
        return searchResponse(List.of());
    }

    private co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> searchResponse(
            List<Hit<Map<String, Object>>> hits) {
        @SuppressWarnings("unchecked")
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> response = mock(
                co.elastic.clients.elasticsearch.core.SearchResponse.class);
        @SuppressWarnings("unchecked")
        HitsMetadata<Map<String, Object>> hitsMetadata = mock(HitsMetadata.class);
        when(hitsMetadata.hits()).thenReturn(hits);
        when(response.hits()).thenReturn(hitsMetadata);
        return response;
    }

    private void assertInvalidCursorIgnored(String decodedCursor, SearchSort sort) throws Exception {
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> response = emptySearchResponse();
        when(es.search(any(Function.class), any(Class.class))).thenReturn(response);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                decodedCursor.getBytes(StandardCharsets.UTF_8));

        PageResponse<FeedItemResponse> result = service.search(
                "cursor", 10, null, cursor, sort, null);

        assertThat(result.degraded()).isFalse();
        assertThat(result.items()).isEmpty();
        verify(es).search(captor.capture(), any(Class.class));
        SearchRequest request = captor.getValue().apply(new SearchRequest.Builder()).build();
        assertThat(request.searchAfter()).isEmpty();
    }

    private static Stream<Arguments> negativeLongSortCursors() {
        return Stream.of(
                Arguments.of(SearchSort.RELEVANCE, "1.25,-1,5,8,99"),
                Arguments.of(SearchSort.RELEVANCE, "1.25,1700000000,-1,8,99"),
                Arguments.of(SearchSort.RELEVANCE, "1.25,1700000000,5,-1,99"),
                Arguments.of(SearchSort.RELEVANCE, "1.25,1700000000,5,8,-1"),
                Arguments.of(SearchSort.NEWEST, "-1,99"),
                Arguments.of(SearchSort.NEWEST, "1700000000,-1"));
    }

    private static Stream<Arguments> malformedSortCursors() {
        return Stream.of(
                Arguments.of(SearchSort.RELEVANCE, "1.25,1700000000,5,8,9223372036854775808"),
                Arguments.of(SearchSort.RELEVANCE, "1.25,1700000000,,8,99"),
                Arguments.of(SearchSort.RELEVANCE, "1.25,not-a-long,5,8,99"),
                Arguments.of(SearchSort.NEWEST, "9223372036854775808,99"));
    }

    private MultiSearchResponseItem<Map<String, Object>> resultItem(
            List<Map<String, Object>> sources,
            Map<String, Aggregate> aggregations) {
        return resultItem(sources, aggregations, sources.size());
    }

    private MultiSearchResponseItem<Map<String, Object>> resultItem(
            List<Map<String, Object>> sources,
            Map<String, Aggregate> aggregations,
            long total) {
        @SuppressWarnings("unchecked")
        MultiSearchResponseItem<Map<String, Object>> item = mock(MultiSearchResponseItem.class);
        @SuppressWarnings("unchecked")
        MultiSearchItem<Map<String, Object>> result = mock(MultiSearchItem.class);
        HitsMetadata<Map<String, Object>> hitsMeta = mock(HitsMetadata.class);
        List<Hit<Map<String, Object>>> hits = sources.stream()
                .map(source -> {
                    @SuppressWarnings("unchecked")
                    Hit<Map<String, Object>> hit = mock(Hit.class);
                    when(hit.source()).thenReturn(source);
                    lenient().when(hit.highlight()).thenReturn(null);
                    return hit;
                })
                .toList();
        lenient().when(hitsMeta.hits()).thenReturn(hits);
        lenient().when(hitsMeta.total()).thenReturn(TotalHits.of(t -> t.value(total).relation(TotalHitsRelation.Eq)));
        lenient().when(result.hits()).thenReturn(hitsMeta);
        lenient().when(result.aggregations()).thenReturn(aggregations);
        when(item.isFailure()).thenReturn(false);
        when(item.result()).thenReturn(result);
        return item;
    }

    private Map<String, Object> postSource(long id, String title, String slug) {
        Map<String, Object> source = new HashMap<>();
        source.put("content_id", id);
        source.put("title", title);
        source.put("slug", slug);
        source.put("description", "desc");
        source.put("tags", List.of("anime"));
        source.put("like_count", 1L);
        source.put("favorite_count", 2L);
        source.put("author_nickname", "tester");
        return source;
    }

    private Map<String, Object> experienceSource(String text) {
        Map<String, Object> source = new HashMap<>();
        source.put("text", text);
        source.put("valueScore", 0.8);
        source.put("importance", 8);
        source.put("createdAt", "2026-07-05T00:00:00Z");
        source.put("source", "test");
        return source;
    }

    private Aggregate hotTagsAggregate() {
        StringTermsBucket bucket = StringTermsBucket.of(b -> b.key("anime").docCount(3L));
        StringTermsAggregate aggregate = StringTermsAggregate.of(a -> a.buckets(b -> b.array(List.of(bucket))));
        return new Aggregate(aggregate);
    }
}
