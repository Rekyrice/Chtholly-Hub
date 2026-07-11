package com.chtholly.agent.content;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.chtholly.content.ContentAnalysis;
import com.chtholly.content.Entity;
import com.chtholly.content.RelatedPostDto;
import com.chtholly.post.model.Post;
import com.chtholly.post.service.PostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentUnderstandingServiceTest {

    private PostService postService;
    private ElasticsearchClient esClient;
    private ContentUnderstandingService service;

    @BeforeEach
    void setUp() {
        postService = mock(PostService.class);
        esClient = mock(ElasticsearchClient.class);
        ContentUnderstandingService.TextGenerator textGenerator = prompt -> {
            if (prompt.contains("提取所有提到的实体")) {
                return """
                        [
                          {"name":"芙莉莲","category":"动漫作品名","confidence":0.92},
                          {"name":"时间","category":"其他专有名词","confidence":0.82},
                          {"name":"噪声","category":"其他专有名词","confidence":0.4}
                        ]
                        """;
            }
            return "原来是在说时间留下来的重量呢。";
        };
        service = new ContentUnderstandingService(
                postService,
                esClient,
                new ObjectMapper().findAndRegisterModules(),
                textGenerator,
                post -> "这是一篇关于芙莉莲、时间和记忆的文章。",
                java.time.Clock.fixed(Instant.parse("2026-07-04T08:00:00Z"), java.time.ZoneOffset.UTC));
    }

    @Test
    void scanAndUnderstandAnalyzesPendingPostsAndPersistsResult() throws Exception {
        Post post = Post.builder()
                .id(42L)
                .title("时间的重量")
                .contentUrl("https://example.com/post.md")
                .build();
        when(postService.getPostsNeedingUnderstanding()).thenReturn(List.of(post));
        mockRelatedSearch(hit(99L, "另一篇时间文章", List.of(
                Map.of("name", "芙莉莲"),
                Map.of("name", "时间")
        )));

        service.scanAndUnderstand();

        var captor = org.mockito.ArgumentCaptor.forClass(ContentAnalysis.class);
        verify(postService).saveContentAnalysis(org.mockito.ArgumentMatchers.eq(42L), captor.capture());
        ContentAnalysis analysis = captor.getValue();
        assertThat(analysis.summary()).isEqualTo("原来是在说时间留下来的重量呢。");
        assertThat(analysis.entities())
                .extracting(Entity::name)
                .containsExactly("芙莉莲", "时间", "噪声");
        assertThat(analysis.relatedPostIds()).containsExactly(99L);
        verify(esClient).update(any(Function.class), org.mockito.ArgumentMatchers.eq(Map.class));
    }

    @Test
    void getRelatedPostsReturnsSharedEntitiesFromAnalysis() {
        ContentAnalysis analysis = new ContentAnalysis(
                List.of(
                        new Entity("芙莉莲", "动漫作品名", 0.9),
                        new Entity("时间", "其他专有名词", 0.8)
                ),
                "当前文章摘要",
                List.of(99L),
                Instant.parse("2026-07-04T08:00:00Z"));
        when(postService.getContentAnalysis(42L)).thenReturn(analysis);
        when(postService.getContentAnalysis(99L)).thenReturn(new ContentAnalysis(
                List.of(
                        new Entity("芙莉莲", "动漫作品名", 0.92),
                        new Entity("时间", "其他专有名词", 0.83)
                ),
                "相关摘要",
                List.of(),
                Instant.parse("2026-07-04T08:00:00Z")));
        when(postService.getDetail(99L, null)).thenReturn(new com.chtholly.post.api.dto.PostDetailResponse(
                "99", "related", "另一篇时间文章", "desc", "url", List.of(), List.of(),
                "1", null, "作者", "[]", 0L, 0L, false, false, false,
                "public", "article", Instant.parse("2026-07-04T08:00:00Z")));

        List<RelatedPostDto> related = service.getRelatedPosts(42L);

        assertThat(related).hasSize(1);
        assertThat(related.getFirst().id()).isEqualTo(99L);
        assertThat(related.getFirst().title()).isEqualTo("另一篇时间文章");
        assertThat(related.getFirst().summary()).isEqualTo("相关摘要");
        assertThat(related.getFirst().sharedEntities()).containsExactlyInAnyOrder("芙莉莲", "时间");
    }

    @Test
    void getAnalysisBySlugDelegatesToPostService() {
        ContentAnalysis analysis = new ContentAnalysis(
                List.of(new Entity("Frieren", "work", 0.9)),
                "post slug summary",
                List.of(),
                Instant.parse("2026-07-04T08:00:00Z"));
        when(postService.getContentAnalysisBySlug("frieren-review")).thenReturn(analysis);

        assertThat(service.getAnalysisBySlug("frieren-review")).isSameAs(analysis);
    }

    private void mockRelatedSearch(Hit<Map<String, Object>> hit) throws Exception {
        @SuppressWarnings("unchecked")
        SearchResponse<Map<String, Object>> response = mock(SearchResponse.class);
        @SuppressWarnings("unchecked")
        HitsMetadata<Map<String, Object>> hits = mock(HitsMetadata.class);
        when(hits.hits()).thenReturn(List.of(hit));
        when(response.hits()).thenReturn(hits);
        when(esClient.search(any(Function.class), org.mockito.ArgumentMatchers.eq(Map.class))).thenReturn(response);
    }

    private Hit<Map<String, Object>> hit(Long id, String title, List<Map<String, Object>> entities) {
        @SuppressWarnings("unchecked")
        Hit<Map<String, Object>> hit = mock(Hit.class);
        when(hit.source()).thenReturn(Map.of(
                "content_id", id,
                "title", title,
                "contentAnalysis", Map.of("entities", entities)
        ));
        return hit;
    }
}
