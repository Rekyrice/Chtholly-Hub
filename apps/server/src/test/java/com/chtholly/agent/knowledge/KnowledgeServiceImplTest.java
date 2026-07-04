package com.chtholly.agent.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.chtholly.agent.search.SearchResult;
import com.chtholly.bangumi.model.BangumiSubjectRow;
import com.chtholly.bangumi.service.BangumiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeServiceImplTest {

    @Test
    void loadsMarkdownChunksAndReturnsRelevantKnowledge() throws Exception {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        when(resolver.getResources("classpath*:knowledge/*.md")).thenReturn(new Resource[]{
                markdown("about-me.md", "# 关于我自己\n\n我叫珂朵莉。我认真到笨拙，不会编造答案，也很在意约定。"),
                markdown("those-stories.md", "# 关于那些故事\n\n《葬送的芙莉莲》让我想到时间、记忆和迟来的理解。")
        });

        KnowledgeServiceImpl service = new KnowledgeServiceImpl(
                resolver,
                emptyProvider(ElasticsearchClient.class),
                emptyProvider(BangumiService.class));
        service.loadKnowledge();

        List<String> results = service.searchRelevantKnowledge("珂朵莉是什么性格", 2);

        assertThat(results)
                .hasSize(1)
                .first()
                .asString()
                .contains("认真到笨拙", "不会编造答案");
    }

    @Test
    void searchEntitiesDelegatesToBangumiSubjects() throws Exception {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        when(resolver.getResources("classpath*:knowledge/*.md")).thenReturn(new Resource[0]);
        BangumiService bangumiService = mock(BangumiService.class);
        BangumiSubjectRow row = new BangumiSubjectRow();
        row.setId(265L);
        row.setName("Sousou no Frieren");
        row.setNameCn("葬送的芙莉莲");
        row.setScore(new BigDecimal("8.7"));
        row.setRank(20);
        row.setEpsCount(28);
        row.setSummary("关于旅途、时间与记忆的故事。");
        when(bangumiService.search("芙莉莲", 3)).thenReturn(List.of(row));

        KnowledgeServiceImpl service = new KnowledgeServiceImpl(
                resolver,
                emptyProvider(ElasticsearchClient.class),
                provider(bangumiService));

        List<SearchResult> results = service.searchEntities("芙莉莲", 3);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getId()).isEqualTo("bangumi:265");
        assertThat(results.getFirst().getTitle()).isEqualTo("葬送的芙莉莲");
        assertThat(results.getFirst().getSnippet()).contains("评分 8.7", "排名 20", "集数 28");
    }

    private static Resource markdown(String filename, String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static <T> ObjectProvider<T> emptyProvider(Class<T> ignoredType) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
