package com.chtholly.agent.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.chtholly.agent.content.ContentUnderstandingService;
import com.chtholly.content.ContentIntelligenceReader;
import com.chtholly.post.service.PostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ContentContractConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void disabledContentExtensionProvidesOnlyEmptyReaderWithoutServiceDependencies() {
        contextRunner
                .withPropertyValues("agent.extensions.content.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ContentIntelligenceReader.class);
                    ContentIntelligenceReader reader = context.getBean(ContentIntelligenceReader.class);
                    assertThat(reader).isNotInstanceOf(ContentUnderstandingService.class);
                    assertThat(reader.getAnalysis(42L)).isNull();
                    assertThat(reader.getAnalysisBySlug("post-slug")).isNull();
                    assertThat(reader.getRelatedPosts(42L)).isEmpty();
                });
    }

    @Test
    void defaultConfigurationProvidesOnlyContentUnderstandingService() {
        withServiceDependencies().run(context -> assertContentUnderstandingService(context));
    }

    @Test
    void enabledContentExtensionProvidesOnlyContentUnderstandingService() {
        withServiceDependencies()
                .withPropertyValues("agent.extensions.content.enabled=true")
                .run(context -> assertContentUnderstandingService(context));
    }

    private ApplicationContextRunner withServiceDependencies() {
        return contextRunner
                .withBean(PostService.class, () -> mock(PostService.class))
                .withBean(ElasticsearchClient.class, () -> mock(ElasticsearchClient.class))
                .withBean(ObjectMapper.class, () -> mock(ObjectMapper.class));
    }

    private void assertContentUnderstandingService(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        assertThat(context).hasNotFailed().hasSingleBean(ContentIntelligenceReader.class);
        assertThat(context.getBean(ContentIntelligenceReader.class))
                .isInstanceOf(ContentUnderstandingService.class);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ContentUnderstandingService.class, ContentContractConfiguration.class})
    static class TestConfiguration {
    }
}
