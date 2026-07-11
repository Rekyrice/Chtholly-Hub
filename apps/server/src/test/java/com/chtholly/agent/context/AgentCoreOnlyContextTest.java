package com.chtholly.agent.context;

import com.chtholly.agent.CharacterSoulService;
import com.chtholly.agent.anchor.AnchorManager;
import com.chtholly.agent.anchor.KnowledgeService;
import com.chtholly.agent.cognitive.CognitiveEngine;
import com.chtholly.agent.cognitive.ExperienceService;
import com.chtholly.agent.comment.CommentGenerationService;
import com.chtholly.agent.config.ContentContractConfiguration;
import com.chtholly.agent.content.ContentUnderstandingService;
import com.chtholly.agent.context.contributor.HistoryContextContributor;
import com.chtholly.agent.context.contributor.IdentityContextContributor;
import com.chtholly.agent.context.contributor.KnowledgeContextContributor;
import com.chtholly.agent.context.contributor.PageContextContributor;
import com.chtholly.agent.context.contributor.ProceduralContextContributor;
import com.chtholly.agent.context.contributor.QuestionContextContributor;
import com.chtholly.agent.context.contributor.RelationshipContextContributor;
import com.chtholly.agent.context.contributor.ToolsContextContributor;
import com.chtholly.agent.learning.InsightService;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.agent.state.CharacterState;
import com.chtholly.agent.state.CharacterStateService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCoreOnlyContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CoreConfiguration.class)
            .withPropertyValues(
                    "agent.extensions.content.enabled=false",
                    "agent.extensions.graph.enabled=false",
                    "agent.extensions.learning.enabled=false",
                    "agent.extensions.experience.enabled=false",
                    "agent.extensions.mood.enabled=false",
                    "agent.extensions.community-actions.enabled=false",
                    "agent.extensions.proactive.enabled=false");

    @Test
    void coreContextBuildsStablePromptWithoutExtensionBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ContextEngine.class);
            assertThat(context).getBeans(ContextContributor.class).hasSize(8);

            String prompt = context.getBean(ContextEngine.class)
                    .buildSystemPrompt(7L, "core-session", "current page", "current question");

            assertThat(prompt)
                    .contains("fixed identity")
                    .contains("\"action\":\"final\"")
                    .contains("current question");
            assertThat(context).doesNotHaveBean(ContentUnderstandingService.class);
            assertThat(context).doesNotHaveBean(InsightService.class);
            assertThat(context).doesNotHaveBean(ExperienceService.class);
            assertThat(context).doesNotHaveBean(CommentGenerationService.class);
            assertThat(context).doesNotHaveBean(NotificationService.class);
            assertThat(context).doesNotHaveBean(CognitiveEngine.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            AnchorManager.class, ContextEngine.class,
            IdentityContextContributor.class, RelationshipContextContributor.class,
            ProceduralContextContributor.class, KnowledgeContextContributor.class,
            PageContextContributor.class, ToolsContextContributor.class,
            HistoryContextContributor.class, QuestionContextContributor.class,
            ContentContractConfiguration.class
    })
    static class CoreConfiguration {

        @Bean
        CharacterSoulService characterSoulService() {
            CharacterSoulService service = mock(CharacterSoulService.class);
            when(service.getSoulContent()).thenReturn("fixed identity");
            return service;
        }

        @Bean
        KnowledgeService knowledgeService() {
            KnowledgeService service = mock(KnowledgeService.class);
            when(service.getRelevantKnowledge(7L, "core-session")).thenReturn(List.of());
            return service;
        }

        @Bean
        CharacterStateService characterStateService() {
            CharacterStateService service = mock(CharacterStateService.class);
            when(service.load(7L)).thenReturn(CharacterState.defaultState());
            return service;
        }
    }
}
