package com.chtholly.agent;

import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.context.ContextEngine;
import com.chtholly.agent.memory.AgentMemoryCommitter;
import com.chtholly.agent.memory.AgentMemoryStore;
import com.chtholly.agent.observability.AgentMetrics;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.observability.AgentTurnTraceLifecycle;
import com.chtholly.agent.response.AgentBoundaryResponseService;
import com.chtholly.agent.response.AgentFinalAnswerPromptFactory;
import com.chtholly.agent.response.AgentFinalAnswerProtocol;
import com.chtholly.agent.response.AgentFinalAnswerRepairService;
import com.chtholly.agent.response.AgentFinalAnswerService;
import com.chtholly.agent.response.AgentFinalAnswerValidationPipeline;
import com.chtholly.agent.response.AgentFinalCandidateGenerator;
import com.chtholly.agent.runtime.AgentActionParser;
import com.chtholly.agent.runtime.AgentBoundedCallExecutor;
import com.chtholly.agent.runtime.AgentContextPreparationService;
import com.chtholly.agent.runtime.AgentDecisionGateway;
import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.runtime.AgentLoopCompletionPolicy;
import com.chtholly.agent.runtime.AgentLoopExecutor;
import com.chtholly.agent.runtime.AgentPreparationSpanLifecycle;
import com.chtholly.agent.runtime.AgentToolCallService;
import com.chtholly.agent.runtime.AgentToolExecutor;
import com.chtholly.agent.runtime.AgentToolPlanner;
import com.chtholly.agent.runtime.AgentTurnCompletion;
import com.chtholly.agent.runtime.AgentTurnOrchestrator;
import com.chtholly.agent.runtime.AgentTurnPlanningService;
import com.chtholly.agent.runtime.AgentTurnPreparationService;
import com.chtholly.agent.runtime.AgentTurnResponseService;
import com.chtholly.agent.skill.SkillOutputValidator;
import com.chtholly.agent.skill.SkillRegistry;
import com.chtholly.agent.skill.SkillRequestPlanner;
import com.chtholly.agent.skill.SkillSelector;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.agent.trace.TracePersistenceService;
import com.chtholly.agent.ws.AgentSessionRateLimiter;
import com.chtholly.agent.ws.AgentTurnCoordinator;
import com.chtholly.agent.ws.AgentWebSocketAcceptedTurnRunner;
import com.chtholly.agent.ws.AgentWebSocketConnectionLifecycle;
import com.chtholly.agent.ws.AgentWebSocketConnectionRegistry;
import com.chtholly.agent.ws.AgentWebSocketDeliveryService;
import com.chtholly.agent.ws.AgentWebSocketExtensionLifecycle;
import com.chtholly.agent.ws.AgentWebSocketHandler;
import com.chtholly.agent.ws.AgentWebSocketHeartbeat;
import com.chtholly.agent.ws.AgentWebSocketProtocolCodec;
import com.chtholly.agent.ws.AgentWebSocketProtocolDispatcher;
import com.chtholly.agent.ws.AgentWebSocketTaskExecutor;
import com.chtholly.agent.ws.AgentWebSocketTurnAdmissionService;
import com.chtholly.agent.ws.AgentWebSocketTurnSubmissionService;
import com.chtholly.agent.ws.AgentWsTicketStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentApplicationServiceContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void omitsConditionalAgentGraphWhenLlmIsDisabled() {
        contextRunner
                .withPropertyValues("llm.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    conditionalAgentGraphTypes().forEach(type ->
                            assertThat(context).doesNotHaveBean(type));
                });
    }

    @Test
    void wiresCompleteAgentAndWebSocketGraphWhenLlmIsEnabled() {
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
                    completeAgentGraphTypes().forEach(type ->
                            assertThat(context).hasSingleBean(type));
                });
    }

    private List<Class<?>> conditionalAgentGraphTypes() {
        return List.of(
                ChthollyAgent.class,
                AgentTurnOrchestrator.class,
                AgentTurnPreparationService.class,
                AgentTurnPlanningService.class,
                AgentContextPreparationService.class,
                AgentPreparationSpanLifecycle.class,
                AgentBoundedCallExecutor.class,
                AgentToolPlanner.class,
                AgentActionParser.class,
                AgentDecisionGateway.class,
                AgentLlmInvoker.class,
                AgentToolExecutor.class,
                AgentToolCallService.class,
                AgentLoopCompletionPolicy.class,
                AgentLoopExecutor.class,
                AgentTurnResponseService.class,
                AgentFinalCandidateGenerator.class,
                AgentFinalAnswerValidationPipeline.class,
                AgentFinalAnswerService.class,
                AgentFinalAnswerPromptFactory.class,
                AgentFinalAnswerProtocol.class,
                AgentFinalAnswerRepairService.class,
                AgentBoundaryResponseService.class,
                AgentMemoryCommitter.class,
                AgentTurnCompletion.class,
                AgentTurnTraceLifecycle.class,
                AgentTurnCoordinator.class,
                AgentWsTicketStore.class,
                AgentWebSocketProtocolCodec.class,
                AgentWebSocketConnectionRegistry.class,
                AgentWebSocketDeliveryService.class,
                AgentWebSocketExtensionLifecycle.class,
                AgentWebSocketTurnAdmissionService.class,
                AgentWebSocketAcceptedTurnRunner.class,
                AgentWebSocketTurnSubmissionService.class,
                AgentWebSocketProtocolDispatcher.class,
                AgentWebSocketConnectionLifecycle.class,
                AgentWebSocketTaskExecutor.class,
                AgentWebSocketHandler.class);
    }

    private List<Class<?>> completeAgentGraphTypes() {
        List<Class<?>> types = new ArrayList<>(conditionalAgentGraphTypes());
        types.addAll(List.of(
                AgentJsonExtractor.class,
                AgentSessionRateLimiter.class,
                AgentWebSocketHeartbeat.class));
        return List.copyOf(types);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            ChthollyAgent.class,
            AgentTurnOrchestrator.class,
            AgentTurnPreparationService.class,
            AgentTurnPlanningService.class,
            AgentContextPreparationService.class,
            AgentPreparationSpanLifecycle.class,
            AgentBoundedCallExecutor.class,
            AgentDecisionGateway.class,
            AgentLlmInvoker.class,
            AgentToolExecutor.class,
            AgentToolCallService.class,
            AgentActionParser.class,
            AgentJsonExtractor.class,
            AgentLoopCompletionPolicy.class,
            AgentLoopExecutor.class,
            AgentTurnResponseService.class,
            AgentToolPlanner.class,
            AgentFinalCandidateGenerator.class,
            AgentFinalAnswerValidationPipeline.class,
            AgentFinalAnswerService.class,
            AgentFinalAnswerPromptFactory.class,
            AgentFinalAnswerProtocol.class,
            AgentFinalAnswerRepairService.class,
            AgentBoundaryResponseService.class,
            AgentMemoryCommitter.class,
            AgentTurnCompletion.class,
            AgentTurnTraceLifecycle.class,
            AgentTurnCoordinator.class,
            AgentWsTicketStore.class,
            AgentSessionRateLimiter.class,
            AgentWebSocketHeartbeat.class,
            AgentWebSocketProtocolCodec.class,
            AgentWebSocketConnectionRegistry.class,
            AgentWebSocketDeliveryService.class,
            AgentWebSocketExtensionLifecycle.class,
            AgentWebSocketTurnAdmissionService.class,
            AgentWebSocketAcceptedTurnRunner.class,
            AgentWebSocketTurnSubmissionService.class,
            AgentWebSocketProtocolDispatcher.class,
            AgentWebSocketConnectionLifecycle.class,
            AgentWebSocketTaskExecutor.class,
            AgentWebSocketHandler.class
    })
    static class TestConfiguration {

        @Bean
        AgentTool agentTool() {
            return mock(AgentTool.class);
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
        SkillOutputValidator skillOutputValidator() {
            return mock(SkillOutputValidator.class);
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
        AgentMetrics agentMetrics() {
            return mock(AgentMetrics.class);
        }

        @Bean
        AgentProperties agentProperties() {
            return new AgentProperties();
        }

        @Bean
        AgentDomainConfig agentDomainConfig() {
            return mock(AgentDomainConfig.class);
        }

        @Bean
        CharacterSoulService characterSoulService() {
            return mock(CharacterSoulService.class);
        }

        @Bean
        CharacterStateService characterStateService() {
            return mock(CharacterStateService.class);
        }

        @Bean
        AgentMemoryStore agentMemoryStore() {
            return mock(AgentMemoryStore.class);
        }

        @Bean
        TracePersistenceService tracePersistenceService() {
            return mock(TracePersistenceService.class);
        }

        @Bean
        ChatClient chatClient() {
            return mock(ChatClient.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
