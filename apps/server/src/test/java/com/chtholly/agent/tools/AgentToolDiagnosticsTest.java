package com.chtholly.agent.tools;

import com.chtholly.agent.ParamDef;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.BangumiDomainConfig;
import com.chtholly.agent.observability.AgentToolDiagnostics;
import com.chtholly.bangumi.service.BangumiService;
import com.chtholly.llm.rag.RagQueryService;
import com.chtholly.search.service.SearchService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolDiagnosticsTest {

    @Test
    void structuredAttributesKeepHttpBackgroundButRedactCredentials() {
        AgentToolDiagnostics diagnostics = AgentToolDiagnostics.fallback("web_fetch", "page")
                .withAttributes(Map.of(
                        "requestedUrl", "https://example.com/review",
                        "tokenBudget", 2_048,
                        "redirectChain", List.of(Map.of(
                                "status", 302,
                                "url", "https://example.com/final")),
                        "resolvedAddresses", List.of("93.184.216.34"),
                        "authorization", "Bearer private"));

        assertThat(diagnostics.attributes())
                .containsEntry("requestedUrl", "https://example.com/review")
                .containsEntry("tokenBudget", 2_048)
                .containsEntry("authorization", "[REDACTED]");
        assertThat(diagnostics.attributes().get("redirectChain").toString())
                .contains("302", "https://example.com/final");
        assertThat(diagnostics.attributes().get("resolvedAddresses").toString())
                .contains("93.184.216.34");
    }

    @Test
    void standardDiagnosticsRedactInfrastructureUserInfoFromInputAndOutputPreview() {
        AgentToolDiagnostics diagnostics = AgentToolDiagnostics.standard(
                "database_probe",
                Map.of(
                        "endpoint", new ParamDef("Database endpoint", String.class, true),
                        "documentation", new ParamDef("Documentation URL", String.class, false)),
                Map.of(
                        "endpoint", "redis://default:redis-secret@cache.internal:6379/0",
                        "documentation", "https://example.com/database/setup"),
                "probe result postgresql://db-user:pg-secret@db.internal/app remains readable");

        assertThat(diagnostics.sanitizedInput())
                .containsEntry("endpoint", "redis://[REDACTED]@cache.internal:6379/0")
                .containsEntry("documentation", "https://example.com/database/setup");
        assertThat(diagnostics.outputPreview())
                .contains("probe result", "postgresql://[REDACTED]@db.internal/app", "remains readable")
                .doesNotContain("db-user", "pg-secret");
        assertThat(diagnostics.toString()).doesNotContain("default", "redis-secret", "db-user", "pg-secret");
    }

    @Test
    void articleRagReportsMetadataActualBlocksAndOrderedUniqueIds() {
        ArticleRagTool tool = new ArticleRagTool(mock(RagQueryService.class));
        String observation = """
                《时间的重量》 (post:42)
                first chunk

                ---

                《另一篇》 (post:7)
                second chunk

                ---

                《时间的重量》 (post:42)
                third chunk""";

        AgentToolDiagnostics diagnostics = tool.traceDiagnostics(
                Map.of("query", "time", "topK", 5, "_userQuestion", "internal secret"),
                observation);

        assertMetadata(diagnostics, "semantic_article_search", "chtholly_rag", "public_index");
        assertThat(diagnostics.resultCount()).isEqualTo(3);
        assertThat(diagnostics.selectedIds()).containsExactly("post:42", "post:7");
        assertThat(diagnostics.sanitizedInput()).containsOnlyKeys("query", "topK");
        assertThat(diagnostics.sanitizedInput()).doesNotContainValue("internal secret");
    }

    @Test
    void articleRagDistinguishesNoResultsFromErrorsAndUnknownOutput() {
        ArticleRagTool tool = new ArticleRagTool(mock(RagQueryService.class));

        assertThat(tool.traceDiagnostics(Map.of(), "未找到与「time」相关且当前可公开访问的帖子片段。").resultCount())
                .isZero();
        assertThat(tool.traceDiagnostics(Map.of(), "错误：缺少参数 query").resultCount()).isNull();
        assertThat(tool.traceDiagnostics(Map.of(), null).resultCount()).isNull();
    }

    @Test
    void fulltextSearchReportsStablePostRoutesInsteadOfInventedDatabaseIds() {
        FulltextSearchTool tool = new FulltextSearchTool(mock(SearchService.class));
        String observation = """
                - 《Frieren》 (/post/frieren)
                  摘要：first

                - 《Journey》 (/post/journey-2)
                  摘要：second""";

        AgentToolDiagnostics diagnostics = tool.traceDiagnostics(Map.of("q", "frieren"), observation);

        assertMetadata(diagnostics, "published_post_search", "chtholly_search", "elasticsearch_or_degraded");
        assertThat(diagnostics.resultCount()).isEqualTo(2);
        assertThat(diagnostics.selectedIds()).containsExactly("/post/frieren", "/post/journey-2");
    }

    @Test
    void fulltextSearchOnlyCountsRecognizableSuccessItems() {
        FulltextSearchTool tool = new FulltextSearchTool(mock(SearchService.class));

        assertThat(tool.traceDiagnostics(Map.of(), "未找到与「x」相关的帖子。").resultCount()).isZero();
        assertThat(tool.traceDiagnostics(Map.of(), "错误：缺少参数 q（搜索关键词）").resultCount()).isNull();
        assertThat(tool.traceDiagnostics(Map.of(), "degraded without recognizable items").resultCount()).isNull();
    }

    @Test
    void bangumiSearchKeepsSeasonTotalWhileTheDiagnosticsContractCapsIds() {
        BangumiSearchTool tool = new BangumiSearchTool(mock(BangumiService.class), domainConfig());
        String items = IntStream.rangeClosed(1, 25)
                .mapToObj(id -> "- 「Season " + id + "」 [Bangumi " + id + "]")
                .collect(Collectors.joining("\n\n"));

        AgentToolDiagnostics diagnostics = tool.traceDiagnostics(
                Map.of("keyword", "series", "_conversationHistory", "private history"),
                "共找到 25 部相关动画：\n\n" + items);

        assertMetadata(diagnostics, "subject_search", "bangumi", "local_first_api_fallback");
        assertThat(diagnostics.resultCount()).isEqualTo(25);
        assertThat(diagnostics.selectedIds()).hasSize(20);
        assertThat(diagnostics.selectedIds()).containsExactlyElementsOf(
                IntStream.rangeClosed(1, 20).mapToObj(String::valueOf).toList());
        assertThat(diagnostics.sanitizedInput()).containsOnlyKeys("keyword");
    }

    @Test
    void bangumiSearchCountsMarkersForOrdinaryResultsButNotErrorsOrUnknownText() {
        BangumiSearchTool tool = new BangumiSearchTool(mock(BangumiService.class), domainConfig());

        AgentToolDiagnostics success = tool.traceDiagnostics(
                Map.of("keyword", "x"),
                "- 「A」 [Bangumi 101]\n\n- 「B」 [Bangumi 202]\n\n- 「A」 [Bangumi 101]");

        assertThat(success.resultCount()).isEqualTo(3);
        assertThat(success.selectedIds()).containsExactly("101", "202");
        assertThat(tool.traceDiagnostics(Map.of(), "Bangumi API 暂时不可用").resultCount()).isNull();
        assertThat(tool.traceDiagnostics(Map.of(), "没有可识别的条目").resultCount()).isNull();
    }

    @Test
    void bangumiSearchIgnoresMarkersOutsideCompleteResultTitleLines() {
        BangumiSearchTool tool = new BangumiSearchTool(mock(BangumiService.class), domainConfig());

        AgentToolDiagnostics unknown = tool.traceDiagnostics(
                Map.of("keyword", "x"), "未知响应 [Bangumi 123]");
        AgentToolDiagnostics summary = tool.traceDiagnostics(
                Map.of("keyword", "x"), "摘要：正文引用 [Bangumi 456]");
        AgentToolDiagnostics errorDetail = tool.traceDiagnostics(
                Map.of("keyword", "x"), "- 错误详情 [Bangumi 123]");

        assertThat(unknown.resultCount()).isNull();
        assertThat(unknown.selectedIds()).isEmpty();
        assertThat(summary.resultCount()).isNull();
        assertThat(summary.selectedIds()).isEmpty();
        assertThat(errorDetail.resultCount()).isNull();
        assertThat(errorDetail.selectedIds()).isEmpty();
    }

    @Test
    void bangumiSearchOnlyTrustsSeasonHeaderWhenACompleteResultTitleExists() {
        BangumiSearchTool tool = new BangumiSearchTool(mock(BangumiService.class), domainConfig());

        AgentToolDiagnostics diagnostics = tool.traceDiagnostics(
                Map.of("keyword", "x"),
                "共找到 25 部相关动画：\n\n摘要：正文引用 [Bangumi 456]");

        assertThat(diagnostics.resultCount()).isNull();
        assertThat(diagnostics.selectedIds()).isEmpty();
    }

    @Test
    void bangumiCharactersUsesOnlyTheSubjectHeaderAndNeverInventsCharacterIds() {
        BangumiCharactersTool tool = new BangumiCharactersTool(mock(BangumiService.class), domainConfig());
        String observation = """
                条目：《小市民系列》[Bangumi 390555]
                登场角色（共 2 个）：
                - 小鸠常悟 [Bangumi 9001]
                - 堂岛健吾 [Bangumi 9002]""";

        AgentToolDiagnostics diagnostics = tool.traceDiagnostics(Map.of("keyword", "小市民"), observation);

        assertMetadata(diagnostics, "subject_characters", "bangumi", "local_subject_then_api");
        assertThat(diagnostics.resultCount()).isEqualTo(2);
        assertThat(diagnostics.selectedIds()).containsExactly("390555");
        assertThat(tool.traceDiagnostics(Map.of(), "Bangumi 未找到相关角色信息。").resultCount()).isNull();
        assertThat(tool.traceDiagnostics(Map.of(), "未知响应").resultCount()).isNull();
    }

    @Test
    void bangumiPersonWorksSumsBlocksAndExcludesPersonIds() {
        BangumiPersonWorksTool tool = new BangumiPersonWorksTool(mock(BangumiService.class));
        String observation = """
                人物：米泽穗信 [Bangumi 100]
                参与作品（全部，共 2 部）：
                - 《小市民系列》[书籍]（作者） [Bangumi 200]
                - 《冰菓》[书籍]（作者） [Bangumi 201]

                人物：某画师 [Bangumi 101]
                参与作品（动画，共 3 部）：
                - 《作品三》[动画] [Bangumi 202]
                - 《冰菓》[动画] [Bangumi 201]
                - 《作品五》[动画] [Bangumi 204]""";

        AgentToolDiagnostics diagnostics = tool.traceDiagnostics(Map.of("keyword", "author"), observation);

        assertMetadata(diagnostics, "person_works", "bangumi", "bounded_api_lookup");
        assertThat(diagnostics.resultCount()).isEqualTo(5);
        assertThat(diagnostics.selectedIds()).containsExactly("200", "201", "202", "204");
        assertThat(diagnostics.selectedIds()).doesNotContain("100", "101");
        assertThat(tool.traceDiagnostics(Map.of(), "Bangumi 未找到相关人物或作品列表。").resultCount()).isNull();
        assertThat(tool.traceDiagnostics(Map.of(), null).resultCount()).isNull();
    }

    private void assertMetadata(
            AgentToolDiagnostics diagnostics,
            String operation,
            String provider,
            String sourcePolicy) {
        assertThat(diagnostics.operation()).isEqualTo(operation);
        assertThat(diagnostics.provider()).isEqualTo(provider);
        assertThat(diagnostics.sourcePolicy()).isEqualTo(sourcePolicy);
    }

    private AgentDomainConfig domainConfig() {
        BangumiDomainConfig bangumi = mock(BangumiDomainConfig.class);
        when(bangumi.keywordParam()).thenReturn("作品关键词");
        AgentDomainConfig config = mock(AgentDomainConfig.class);
        when(config.bangumi()).thenReturn(bangumi);
        return config;
    }
}
