package com.chtholly.agent.search;

import com.chtholly.agent.anchor.KnowledgeService;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.llm.rag.RagQueryService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.search.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridSearchServiceTest {

    private RagQueryService ragService;
    private SearchService searchService;
    private KnowledgeService knowledgeService;
    private HybridSearchService hybridSearchService;

    @BeforeEach
    void setUp() {
        ragService = mock(RagQueryService.class);
        searchService = mock(SearchService.class);
        knowledgeService = mock(KnowledgeService.class);
        hybridSearchService = new HybridSearchService(ragService, searchService, knowledgeService);
    }

    @Test
    void hybridSearchCombinesDeduplicatesAndRanksWithRrf() {
        SearchResult semanticOnly = result("post:1", "Semantic only", "from vector", "semantic");
        SearchResult sharedFromSemantic = result("post:2", "Shared", "semantic snippet", "semantic");
        SearchResult keywordOnly = result("post:3", "Keyword only", "from bm25", "keyword");
        SearchResult entityOnly = result("bangumi:99", "Entity only", "from bangumi", "entity");

        when(ragService.search("frieren time", 6)).thenReturn(List.of(semanticOnly, sharedFromSemantic));
        when(searchService.search("frieren time", 6, null, null, null)).thenReturn(PageResponse.cursor(List.of(
                feed("post:3", "keyword-only", "Keyword only", "from bm25"),
                feed("post:2", "shared", "Shared", "keyword snippet")
        ), 6, false, null));
        when(knowledgeService.searchEntities("frieren time", 3)).thenReturn(List.of(entityOnly));

        List<SearchResult> results = hybridSearchService.hybridSearch("frieren time", 3);

        assertThat(results).extracting(SearchResult::getId)
                .containsExactly("post:2", "post:1", "post:3");
        assertThat(results).hasSize(3);
        assertThat(results.getFirst().getScore())
                .isEqualTo(1.0 / 62 + 1.0 / 62);
        assertThat(results.getFirst().getSnippet()).isEqualTo("semantic snippet");
        verify(ragService).search("frieren time", 6);
        verify(searchService).search("frieren time", 6, null, null, null);
        verify(knowledgeService).searchEntities("frieren time", 3);
    }

    private SearchResult result(String id, String title, String snippet, String source) {
        return new SearchResult(id, title, snippet, source, 0.0);
    }

    private FeedItemResponse feed(String id, String slug, String title, String description) {
        return new FeedItemResponse(
                id,
                slug,
                title,
                description,
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
}
