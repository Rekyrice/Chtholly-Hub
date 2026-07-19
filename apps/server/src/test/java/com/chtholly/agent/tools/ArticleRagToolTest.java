package com.chtholly.agent.tools;

import com.chtholly.agent.search.SearchResult;
import com.chtholly.llm.rag.RagQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleRagToolTest {

    @Test
    void executeUsesTheMysqlAuthorizedRagBoundary() {
        RagQueryService ragQueryService = mock(RagQueryService.class);
        when(ragQueryService.search("time", 5)).thenReturn(List.of(new SearchResult(
                "post:42",
                "时间的重量",
                "verified current chunk",
                "semantic",
                0.8,
                "post:42",
                "42#0",
                "current",
                "sha-42",
                Set.of("PUBLIC"))));
        ArticleRagTool tool = new ArticleRagTool(ragQueryService);

        String result = tool.execute(Map.of("query", "time", "topK", 5), 7L);

        verify(ragQueryService).search("time", 5);
        assertThat(result).contains("时间的重量", "post:42", "verified current chunk");
    }

    @Test
    void executeReturnsNoAnswerWhenNoAuthorizedArticlesRemain() {
        RagQueryService ragQueryService = mock(RagQueryService.class);
        when(ragQueryService.search("secret", 5)).thenReturn(List.of());
        ArticleRagTool tool = new ArticleRagTool(ragQueryService);

        assertThat(tool.execute(Map.of("query", "secret"), 7L))
                .contains("未找到", "公开访问");
    }
}
