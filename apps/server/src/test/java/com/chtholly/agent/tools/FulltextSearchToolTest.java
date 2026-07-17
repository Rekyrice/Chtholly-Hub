package com.chtholly.agent.tools;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.search.service.SearchService;
import com.chtholly.search.service.SearchSort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FulltextSearchToolTest {

    private SearchService searchService;
    private FulltextSearchTool tool;

    @BeforeEach
    void setUp() {
        searchService = mock(SearchService.class);
        tool = new FulltextSearchTool(searchService);
    }

    @Test
    void given_query_when_execute_then_searchesByRelevance() {
        when(searchService.search("frieren", 5, null, null, SearchSort.RELEVANCE, 42L))
                .thenReturn(PageResponse.cursor(List.of(), 5, false, null));

        String result = tool.execute(Map.of("q", " frieren "), 42L);

        assertThat(result).contains("未找到");
        verify(searchService).search("frieren", 5, null, null, SearchSort.RELEVANCE, 42L);
    }
}
