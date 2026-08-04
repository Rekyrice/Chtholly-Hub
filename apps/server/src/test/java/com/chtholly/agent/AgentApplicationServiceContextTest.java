package com.chtholly.agent;

import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.context.ContextEngine;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.response.AgentBoundaryResponseService;
import com.chtholly.agent.response.AgentFinalAnswerPromptFactory;
import com.chtholly.agent.response.AgentFinalAnswerProtocol;
import com.chtholly.agent.response.AgentFinalAnswerRepairService;
import com.chtholly.agent.response.AgentFinalAnswerService;
import com.chtholly.agent.response.AgentFinalAnswerValidationPipeline;
import com.chtholly.agent.response.AgentFinalCandidateGenerator;
import com.chtholly.agent.runtime.AgentBoundedCallExecutor;
import com.chtholly.agent.runtime.AgentContextPreparationService;
import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.runtime.AgentPreparationSpanLifecycle;
import com.chtholly.agent.runtime.AgentToolPlanner;
import com.chtholly.agent.runtime.AgentTurnCompletion;
import com.chtholly.agent.runtime.AgentTurnPlanningService;
import com.chtholly.agent.runtime.AgentTurnPreparationService;
import com.chtholly.agent.skill.SkillOutputValidator;
import com.chtholly.agent.skill.SkillRegistry;
import com.chtholly.agent.skill.SkillRequestPlanner;
import com.chtholly.agent.skill.SkillSelector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentApplicationServiceContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void omitsAgentApplicationServicesWhenLlmIsDisabled() {
        contextRunner
                .withPropertyValues("llm.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    applicationServiceTypes().forEach(type ->
                            assertThat(context).doesNotHaveBean(type));
                });
    }

    @Test
    void wiresAgentApplicationServicesWithoutOptionalExtensions() {
        contextRunner
                .withPropertyValues(
                        "llm.enabled=true",
                        "agent.extensions.content.enabled=false",
                        "agent.extensions.graph.enabled=false",
                        "agent.extensions.learning.enabled=false",
                        "agent.extensions.experience.enabled=false",
                        "agent.extensions.mood.enabled=false",
                        "agent.extensions.community-actions.enabled=false",
                        "agent.extensions.proactive.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    applicationServiceTypes().forEach(type ->
                            assertThat(context).hasSingleBean(type));
                });
    }

    private java.util.List<Class<?>> applicationServiceTypes() {
        return java.util.List.of(
                AgentBoundedCallExecutor.class,
                AgentTurnPlanningService.class,
                AgentContextPreparationService.class,
                AgentPreparationSpanLifecycle.class,
                AgentTurnPreparationService.class,
                AgentFinalCandidateGenerator.class,
                AgentFinalAnswerValidationPipeline.class,
                AgentFinalAnswerService.class);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            AgentBoundedCallExecutor.class,
            AgentTurnPlanningService.class,
            AgentContextPreparationService.class,
            AgentPreparationSpanLifecycle.class,
            AgentTurnPreparationService.class,
            AgentFinalCandidateGenerator.class,
            AgentFinalAnswerValidationPipeline.class,
            AgentFinalAnswerService.class
    })
    static class TestConfiguration {

        @Bean
        AgentTool agentTool() {
            return mock(AgentTool.class);
        }

        @Bean
        AgentToolPlanner agentToolPlanner() {
            return mock(AgentToolPlanner.class);
        }

        @Bean
        SkillRegistry skillRegistry() {
            return mock(SkillRegistry.class);
        }

        @Bean
        SkillSelector skillSelector() {
            return mock(SkillSelector.class);
        }

        @Bean
        SkillRequestPlanner skillRequestPlanner() {
            return mock(SkillRequestPlanner.class);
        }

        @Bean
        ContextEngine contextEngine() {
            return mock(ContextEngine.class);
        }

        @Bean
        AgentObservationService agentObservationService() {
            return mock(AgentObservationService.class);
        }

        @Bean
        AgentLlmInvoker agentLlmInvoker() {
            return mock(AgentLlmInvoker.class);
        }

        @Bean
        AgentProperties agentProperties() {
            return new AgentProperties();
        }

        @Bean
        AgentFinalAnswerPromptFactory agentFinalAnswerPromptFactory() {
            return mock(AgentFinalAnswerPromptFactory.class);
        }

        @Bean
        AgentFinalAnswerProtocol agentFinalAnswerProtocol() {
            return mock(AgentFinalAnswerProtocol.class);
        }

        @Bean
        AgentFinalAnswerRepairService agentFinalAnswerRepairService() {
            return mock(AgentFinalAnswerRepairService.class);
        }

        @Bean
        SkillOutputValidator skillOutputValidator() {
            return mock(SkillOutputValidator.class);
        }

        @Bean
        AgentBoundaryResponseService agentBoundaryResponseService() {
            return mock(AgentBoundaryResponseService.class);
        }

        @Bean
        AgentTurnCompletion agentTurnCompletion() {
            return mock(AgentTurnCompletion.class);
        }
    }
}
