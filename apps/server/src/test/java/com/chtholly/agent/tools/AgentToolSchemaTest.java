package com.chtholly.agent.tools;

import com.chtholly.agent.ParamDef;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.BangumiDomainConfig;
import com.chtholly.bangumi.service.BangumiService;
import com.chtholly.llm.rag.RagQueryService;
import com.chtholly.search.service.SearchService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolSchemaTest {

    @Test
    void articleRagDeclaresBoundedQueryAndTopK() {
        Map<String, ParamDef> schema = new ArticleRagTool(mock(RagQueryService.class))
                .parameterSchema();

        assertThat(schema).containsOnlyKeys("query", "topK");
        assertThat(schema.get("query"))
                .extracting(ParamDef::type, ParamDef::required, ParamDef::minLength, ParamDef::maxLength)
                .containsExactly(String.class, true, 1, 200);
        assertThat(schema.get("topK"))
                .extracting(ParamDef::type, ParamDef::required, ParamDef::minimum, ParamDef::maximum)
                .containsExactly(Integer.class, false, 1L, 10L);
    }

    @Test
    void siteAndBangumiSubjectSearchesDeclareBoundedRequiredKeywords() {
        ParamDef siteQuery = new FulltextSearchTool(mock(SearchService.class))
                .parameterSchema().get("q");
        ParamDef subjectKeyword = new BangumiSearchTool(
                mock(BangumiService.class), domainConfig())
                .parameterSchema().get("keyword");

        assertThat(siteQuery)
                .extracting(ParamDef::type, ParamDef::required, ParamDef::minLength, ParamDef::maxLength)
                .containsExactly(String.class, true, 1, 120);
        assertThat(subjectKeyword)
                .extracting(ParamDef::type, ParamDef::required, ParamDef::minLength, ParamDef::maxLength)
                .containsExactly(String.class, true, 1, 120);
    }

    @Test
    void characterKeywordRemainsOptionalBecauseHistoryCanSupplyTheWork() {
        Map<String, ParamDef> schema = new BangumiCharactersTool(
                mock(BangumiService.class), domainConfig())
                .parameterSchema();

        assertThat(schema).containsOnlyKeys("keyword");
        assertThat(schema.get("keyword"))
                .extracting(ParamDef::type, ParamDef::required, ParamDef::minLength, ParamDef::maxLength)
                .containsExactly(String.class, false, 1, 120);
    }

    @Test
    void personWorksDeclaresOptionalLookupKeysAndClosedWorkTypeEnum() {
        Map<String, ParamDef> schema = new BangumiPersonWorksTool(mock(BangumiService.class))
                .parameterSchema();

        assertThat(schema).containsOnlyKeys("keyword", "work_title", "work_type");
        assertThat(schema.get("keyword"))
                .extracting(ParamDef::type, ParamDef::required, ParamDef::minLength, ParamDef::maxLength)
                .containsExactly(String.class, false, 1, 120);
        assertThat(schema.get("work_title"))
                .extracting(ParamDef::type, ParamDef::required, ParamDef::minLength, ParamDef::maxLength)
                .containsExactly(String.class, false, 1, 120);
        assertThat(schema.get("work_type").enumValues())
                .containsExactly("book", "anime", "all");
    }

    private AgentDomainConfig domainConfig() {
        BangumiDomainConfig bangumi = mock(BangumiDomainConfig.class);
        when(bangumi.keywordParam()).thenReturn("作品关键词");
        AgentDomainConfig config = mock(AgentDomainConfig.class);
        when(config.bangumi()).thenReturn(bangumi);
        return config;
    }
}
