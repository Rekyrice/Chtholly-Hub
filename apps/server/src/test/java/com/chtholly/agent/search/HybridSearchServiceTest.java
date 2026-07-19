package com.chtholly.agent.search;

import com.chtholly.agent.anchor.KnowledgeService;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.llm.rag.RagQueryService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.search.service.SearchService;
import com.chtholly.search.service.SearchSort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridSearchServiceTest {

    private RagQueryService ragService;
    private SearchService searchService;
    private KnowledgeService knowledgeService;
    private PostMapper postMapper;
    private HybridSearchService hybridSearchService;

    @BeforeEach
    void setUp() {
        ragService = mock(RagQueryService.class);
        searchService = mock(SearchService.class);
        knowledgeService = mock(KnowledgeService.class);
        postMapper = mock(PostMapper.class);
        hybridSearchService = new HybridSearchService(
                ragService, searchService, knowledgeService, postMapper);
    }

    @Test
    void fusesThreeArticleRoutesAndLetsEachArticleVoteOncePerRoute() {
        when(ragService.search("frieren time", 8)).thenReturn(List.of(
                semantic(2, "2#0", "sha-2"),
                semantic(2, "2#1", "sha-2"),
                semantic(1, "1#0", "sha-1")));
        when(searchService.search(
                "frieren time", 8, null, null, SearchSort.RELEVANCE, null))
                .thenReturn(page(
                        feed("2", "Shared"),
                        feed("3", "Keyword only")));
        when(knowledgeService.searchEntities("frieren time", 4)).thenReturn(List.of(
                new SearchResult("bangumi:99", "葬送的芙莉莲", "entity", "entity", 0.0)));
        when(searchService.searchByEntityNames(
                List.of("葬送的芙莉莲"), 8, null))
                .thenReturn(page(
                        feed("2", "Shared"),
                        feed("4", "Entity only")));
        when(postMapper.findByIds(anyList())).thenReturn(List.of(
                post(1, "Semantic only", "sha-1", "public"),
                post(2, "Shared", "sha-2", "public"),
                post(3, "Keyword only", "sha-3", "public"),
                post(4, "Entity only", "sha-4", "public")));

        HybridSearchService.HybridSearchResponse response =
                hybridSearchService.hybridSearch("frieren time", 4);

        assertThat(response.documents()).extracting(SearchResult::getId)
                .containsExactly("post:2", "post:1", "post:3", "post:4");
        assertThat(response.documents().getFirst().getScore()).isEqualTo(3.0 / 61.0);
        assertThat(response.documents().getFirst().getSource())
                .isEqualTo("semantic+keyword+entity");
        assertThat(response.statuses()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "semantic", HybridSearchService.RetrievalStatus.SUCCESS_RESULTS,
                "keyword", HybridSearchService.RetrievalStatus.SUCCESS_RESULTS,
                "entity", HybridSearchService.RetrievalStatus.SUCCESS_RESULTS));
        verify(knowledgeService).searchEntities("frieren time", 4);
        verify(searchService).searchByEntityNames(
                List.of("葬送的芙莉莲"), 8, null);
    }

    @Test
    void dropsPrivateMissingAndStaleCandidatesAgainstMysqlAuthority() {
        when(ragService.search("authority", 6)).thenReturn(List.of(
                semantic(1, "1#0", "old-sha"),
                semantic(2, "2#0", "sha-2"),
                semantic(9, "9#0", "sha-9")));
        when(searchService.search(
                "authority", 6, null, null, SearchSort.RELEVANCE, null))
                .thenReturn(page(feed("3", "Current public")));
        when(knowledgeService.searchEntities("authority", 3)).thenReturn(List.of());
        when(postMapper.findByIds(anyList())).thenReturn(List.of(
                post(1, "Stale", "new-sha", "public"),
                post(2, "Private", "sha-2", "private"),
                post(3, "Current public", "sha-3", "public")));

        HybridSearchService.HybridSearchResponse response =
                hybridSearchService.hybridSearch("authority", 3);

        assertThat(response.documents()).extracting(SearchResult::getId)
                .containsExactly("post:3");
        assertThat(response.statuses())
                .containsEntry("semantic", HybridSearchService.RetrievalStatus.SUCCESS_EMPTY)
                .containsEntry("keyword", HybridSearchService.RetrievalStatus.SUCCESS_RESULTS)
                .containsEntry("entity", HybridSearchService.RetrievalStatus.SUCCESS_EMPTY);
        SearchResult result = response.documents().getFirst();
        assertThat(result.getSourceHash()).isEqualTo("sha-3");
        assertThat(result.getPermissions()).containsExactly("PUBLIC");
    }

    @Test
    void reportsFailedEmptyAndSuccessfulRoutesWithoutHidingDegradation() {
        when(ragService.search("degraded", 4)).thenThrow(new IllegalStateException("vector down"));
        when(searchService.search(
                "degraded", 4, null, null, SearchSort.RELEVANCE, null))
                .thenReturn(new PageResponse<>(List.of(), 0, 4, 0, false, null, true));
        when(knowledgeService.searchEntities("degraded", 2)).thenReturn(List.of());

        HybridSearchService.HybridSearchResponse response =
                hybridSearchService.hybridSearch("degraded", 2);

        assertThat(response.documents()).isEmpty();
        assertThat(response.degraded()).isTrue();
        assertThat(response.statuses())
                .containsEntry("semantic", HybridSearchService.RetrievalStatus.FAILED)
                .containsEntry("keyword", HybridSearchService.RetrievalStatus.FAILED)
                .containsEntry("entity", HybridSearchService.RetrievalStatus.SUCCESS_EMPTY);
    }

    @Test
    void equalRrfScoresUseStableArticleIdTieBreak() {
        when(ragService.search("tie", 4)).thenReturn(List.of(semantic(10, "10#0", "sha-10")));
        when(searchService.search("tie", 4, null, null, SearchSort.RELEVANCE, null))
                .thenReturn(page(feed("2", "First by numeric id")));
        when(knowledgeService.searchEntities("tie", 2)).thenReturn(List.of());
        when(postMapper.findByIds(anyList())).thenReturn(List.of(
                post(2, "First by numeric id", "sha-2", "public"),
                post(10, "Second by numeric id", "sha-10", "public")));

        assertThat(hybridSearchService.hybridSearch("tie", 2).documents())
                .extracting(SearchResult::getId)
                .containsExactly("post:2", "post:10");
    }

    @Test
    void authorityLookupFailureMarksCandidateRoutesFailed() {
        when(ragService.search("db down", 4)).thenReturn(List.of(semantic(1, "1#0", "sha-1")));
        when(searchService.search("db down", 4, null, null, SearchSort.RELEVANCE, null))
                .thenReturn(page());
        when(knowledgeService.searchEntities("db down", 2)).thenReturn(List.of());
        when(postMapper.findByIds(anyList())).thenThrow(new IllegalStateException("mysql down"));

        HybridSearchService.HybridSearchResponse response =
                hybridSearchService.hybridSearch("db down", 2);

        assertThat(response.documents()).isEmpty();
        assertThat(response.statuses())
                .containsEntry("semantic", HybridSearchService.RetrievalStatus.FAILED)
                .containsEntry("keyword", HybridSearchService.RetrievalStatus.SUCCESS_EMPTY)
                .containsEntry("entity", HybridSearchService.RetrievalStatus.SUCCESS_EMPTY);
    }

    private SearchResult semantic(long id, String chunkId, String hash) {
        return new SearchResult(
                "post:" + id,
                "Post " + id,
                "semantic snippet " + chunkId,
                "semantic",
                0.0,
                "post:" + id,
                chunkId,
                "current",
                hash,
                Set.of("PUBLIC"));
    }

    private PageResponse<FeedItemResponse> page(FeedItemResponse... items) {
        return new PageResponse<>(List.of(items), 0, items.length, 0, false, null, false);
    }

    private FeedItemResponse feed(String id, String title) {
        return new FeedItemResponse(
                id,
                "post-" + id,
                title,
                "description-" + id,
                null,
                List.of(),
                null,
                null,
                null,
                0L,
                0L,
                false,
                false,
                false);
    }

    private Post post(long id, String title, String hash, String visible) {
        return Post.builder()
                .id(id)
                .title(title)
                .description("authoritative-" + id)
                .status("published")
                .visible(visible)
                .contentSha256(hash)
                .build();
    }
}
