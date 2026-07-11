package com.chtholly.seed;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.chtholly.admin.bootstrap.AdminRoleBootstrap;
import com.chtholly.admin.bootstrap.OwnerAccountBootstrap;
import com.chtholly.agent.graph.KnowledgeGraphIndexInitializer;
import com.chtholly.common.job.CleanupConfiguration;
import com.chtholly.counter.config.CounterSchedulingConfig;
import com.chtholly.notification.config.NotificationAsyncConfiguration;
import com.chtholly.search.index.SearchIndexInitializer;
import com.chtholly.search.index.SearchIndexService;
import com.chtholly.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SeedCliReadOnlySafetyTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);

    @Test
    void cliReadOnly_doesNotRegisterStartupWritersOrCallMySqlAndElasticsearch() {
        new ApplicationContextRunner()
                .withPropertyValues("seed.cli-read-only=true")
                .withBean(UserMapper.class, () -> userMapper)
                .withBean(ElasticsearchClient.class, () -> elasticsearch)
                .withBean(SearchIndexService.class, () -> mock(SearchIndexService.class))
                .withUserConfiguration(
                        AdminRoleBootstrap.class,
                        OwnerAccountBootstrap.class,
                        SearchIndexInitializer.class,
                        KnowledgeGraphIndexInitializer.class)
                .run(context -> {
                    context.assertThat().doesNotHaveBean(AdminRoleBootstrap.class);
                    context.assertThat().doesNotHaveBean(OwnerAccountBootstrap.class);
                    context.assertThat().doesNotHaveBean(SearchIndexInitializer.class);
                    context.assertThat().doesNotHaveBean(KnowledgeGraphIndexInitializer.class);
                    verifyNoInteractions(userMapper, elasticsearch);
                });
    }

    @Test
    void cliReadOnly_doesNotRegisterAnySchedulingEnabler() {
        new ApplicationContextRunner()
                .withPropertyValues("seed.cli-read-only=true")
                .withUserConfiguration(
                        CleanupConfiguration.class,
                        CounterSchedulingConfig.class,
                        NotificationAsyncConfiguration.class)
                .run(context -> {
                    context.assertThat().doesNotHaveBean(CleanupConfiguration.class);
                    context.assertThat().doesNotHaveBean(CounterSchedulingConfig.class);
                    context.assertThat().doesNotHaveBean(NotificationAsyncConfiguration.class);
                });
    }
}
