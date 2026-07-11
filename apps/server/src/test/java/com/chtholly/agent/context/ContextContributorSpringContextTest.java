package com.chtholly.agent.context;

import com.chtholly.agent.anchor.AnchorManager;
import com.chtholly.agent.context.contributor.HistoryContextContributor;
import com.chtholly.agent.context.contributor.IdentityContextContributor;
import com.chtholly.agent.context.contributor.KnowledgeContextContributor;
import com.chtholly.agent.context.contributor.PageContextContributor;
import com.chtholly.agent.context.contributor.ProceduralContextContributor;
import com.chtholly.agent.context.contributor.QuestionContextContributor;
import com.chtholly.agent.context.contributor.RelationshipContextContributor;
import com.chtholly.agent.context.contributor.ToolsContextContributor;
import com.chtholly.agent.state.CharacterStateService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ContextContributorSpringContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ContextConfiguration.class);

    @Test
    void assemblesExactlyEightOrderedContributorsAndContextEngine() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ContextEngine.class);
            List<ContextContributor> contributors = context.getBeansOfType(ContextContributor.class)
                    .values().stream()
                    .sorted(java.util.Comparator.comparingInt(ContextContributor::order))
                    .toList();

            assertThat(contributors).hasSize(8);
            assertThat(contributors).extracting(ContextContributor::name).containsExactly(
                    "identity",
                    "relationship",
                    "page",
                    "knowledge",
                    "procedural",
                    "tools",
                    "history",
                    "question");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            ContextEngine.class,
            IdentityContextContributor.class,
            RelationshipContextContributor.class,
            PageContextContributor.class,
            KnowledgeContextContributor.class,
            ProceduralContextContributor.class,
            ToolsContextContributor.class,
            HistoryContextContributor.class,
            QuestionContextContributor.class
    })
    static class ContextConfiguration {

        @Bean
        AnchorManager anchorManager() {
            return mock(AnchorManager.class);
        }

        @Bean
        CharacterStateService characterStateService() {
            return mock(CharacterStateService.class);
        }
    }
}
