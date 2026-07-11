package com.chtholly.agent.proactive;

import com.chtholly.agent.api.AgentExperienceController;
import com.chtholly.agent.api.TopicController;
import com.chtholly.agent.cognitive.CognitiveEngine;
import com.chtholly.agent.cognitive.ExperienceService;
import com.chtholly.agent.comment.CommentGenerationService;
import com.chtholly.agent.config.ContentContractConfiguration;
import com.chtholly.agent.content.ContentUnderstandingService;
import com.chtholly.agent.content.TopicClusteringService;
import com.chtholly.agent.experience.ExperienceGenerator;
import com.chtholly.agent.graph.KnowledgeGraphExtractionService;
import com.chtholly.agent.graph.KnowledgeGraphIndexInitializer;
import com.chtholly.agent.graph.KnowledgeGraphService;
import com.chtholly.agent.graph.MyBatisKnowledgeGraphRepository;
import com.chtholly.agent.learning.AgentLearningAsyncConfig;
import com.chtholly.agent.learning.InsightService;
import com.chtholly.agent.memory.ProceduralMemoryService;
import com.chtholly.agent.mood.DefaultInteractionService;
import com.chtholly.agent.mood.MoodEngine;
import com.chtholly.agent.mood.SeasonService;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.content.ContentIntelligenceReader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExtensionConditionTest {

    private static final String[] ALL_EXTENSIONS_DISABLED = {
            "agent.extensions.content.enabled=false",
            "agent.extensions.graph.enabled=false",
            "agent.extensions.learning.enabled=false",
            "agent.extensions.experience.enabled=false",
            "agent.extensions.mood.enabled=false",
            "agent.extensions.community-actions.enabled=false",
            "agent.extensions.proactive.enabled=false"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AllExtensionEntries.class);

    @Test
    void allExtensionsCanBeDisabledWithoutLoadingTheirEntryBeans() {
        contextRunner.withPropertyValues(ALL_EXTENSIONS_DISABLED).run(context -> {
            assertThat(context).hasNotFailed();
            assertAbsent(context,
                    ContentUnderstandingService.class, TopicClusteringService.class, TopicController.class,
                    KnowledgeGraphExtractionService.class, KnowledgeGraphIndexInitializer.class,
                    KnowledgeGraphService.class, MyBatisKnowledgeGraphRepository.class,
                    AgentLearningAsyncConfig.class, InsightService.class, ProceduralMemoryService.class,
                    ExperienceService.class, ExperienceGenerator.class, AgentExperienceController.class,
                    DefaultInteractionService.class, MoodEngine.class, SeasonService.class,
                    CommentGenerationService.class, NotificationService.class,
                    CharacterStateUserActivityProvider.class, ContentProactiveService.class,
                    EmotionalProactiveService.class, PostPublishedProactiveListener.class,
                    ProactiveAudienceService.class, ProactiveNotificationDispatcher.class,
                    ProactiveRateLimiter.class, ProactiveTriggerEngine.class,
                    SeedCurationReader.class, SocialProactiveService.class, CognitiveEngine.class);
        });
    }

    @Test
    void disabledContentExtensionKeepsNeutralReaderFallback() {
        new ApplicationContextRunner()
                .withUserConfiguration(ContentEntries.class)
                .withPropertyValues("agent.extensions.content.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ContentIntelligenceReader.class);
                    assertThat(context).doesNotHaveBean(ContentUnderstandingService.class);
                    assertThat(context.getBean(ContentIntelligenceReader.class))
                            .isNotInstanceOf(ContentUnderstandingService.class);
                });
    }

    @Test
    void proactiveRequiresExperienceEvenWhenProactiveSwitchIsEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProactiveEntries.class)
                .withPropertyValues(
                        "agent.extensions.proactive.enabled=true",
                        "agent.extensions.experience.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertAbsent(context,
                            CharacterStateUserActivityProvider.class, ContentProactiveService.class,
                            EmotionalProactiveService.class, PostPublishedProactiveListener.class,
                            ProactiveAudienceService.class, ProactiveNotificationDispatcher.class,
                            ProactiveRateLimiter.class, ProactiveTriggerEngine.class,
                            SeedCurationReader.class, SocialProactiveService.class);
                });
    }

    @Test
    void cognitiveEngineRequiresExperienceEvenWhenLearningSwitchIsEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(CognitiveEntry.class)
                .withPropertyValues(
                        "agent.extensions.learning.enabled=true",
                        "agent.extensions.experience.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CognitiveEngine.class);
                });
    }

    private static void assertAbsent(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context,
            Class<?>... beanTypes) {
        for (Class<?> beanType : beanTypes) {
            assertThat(context).doesNotHaveBean(beanType);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            ContentContractConfiguration.class,
            ContentUnderstandingService.class, TopicClusteringService.class, TopicController.class,
            KnowledgeGraphExtractionService.class, KnowledgeGraphIndexInitializer.class,
            KnowledgeGraphService.class, MyBatisKnowledgeGraphRepository.class,
            AgentLearningAsyncConfig.class, InsightService.class, ProceduralMemoryService.class,
            ExperienceService.class, ExperienceGenerator.class, AgentExperienceController.class,
            DefaultInteractionService.class, MoodEngine.class, SeasonService.class,
            CommentGenerationService.class, NotificationService.class,
            CharacterStateUserActivityProvider.class, ContentProactiveService.class,
            EmotionalProactiveService.class, PostPublishedProactiveListener.class,
            ProactiveAudienceService.class, ProactiveNotificationDispatcher.class,
            ProactiveRateLimiter.class, ProactiveTriggerEngine.class,
            SeedCurationReader.class, SocialProactiveService.class, CognitiveEngine.class
    })
    static class AllExtensionEntries {
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ContentContractConfiguration.class, ContentUnderstandingService.class})
    static class ContentEntries {
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            CharacterStateUserActivityProvider.class, ContentProactiveService.class,
            EmotionalProactiveService.class, PostPublishedProactiveListener.class,
            ProactiveAudienceService.class, ProactiveNotificationDispatcher.class,
            ProactiveRateLimiter.class, ProactiveTriggerEngine.class,
            SeedCurationReader.class, SocialProactiveService.class
    })
    static class ProactiveEntries {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CognitiveEngine.class)
    static class CognitiveEntry {
    }
}
