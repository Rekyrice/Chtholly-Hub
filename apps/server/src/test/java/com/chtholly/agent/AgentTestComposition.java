package com.chtholly.agent;

import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.context.ContextEngine;
import com.chtholly.agent.memory.AgentMemoryCommitter;
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
import com.chtholly.agent.runtime.AgentBoundedCallExecutor;
import com.chtholly.agent.runtime.AgentActionParser;
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
import com.chtholly.agent.skill.SkillRequestPlanner;
import com.chtholly.agent.skill.SkillRegistry;
import com.chtholly.agent.skill.SkillSelector;
import com.chtholly.agent.trace.TracePersistenceService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** Test-only wiring for focused facade tests that intentionally avoid a Spring context. */
final class AgentTestComposition {

    private AgentTestComposition() {
    }

    static AgentLoopExecutor createLoopExecutor(
            AgentLlmInvoker llmInvoker,
            AgentToolExecutor toolExecutor,
            ObjectMapper objectMapper,
            AgentObservationService observationService,
            AgentDomainConfig domainConfig) {
        AgentActionParser parser = new AgentActionParser(
                new AgentJsonExtractor(objectMapper), objectMapper);
        return new AgentLoopExecutor(
                new AgentDecisionGateway(llmInvoker, observationService),
                new AgentToolCallService(
                        toolExecutor, parser, observationService, domainConfig),
                parser,
                new AgentLoopCompletionPolicy(),
                objectMapper,
                domainConfig);
    }

    static ChthollyAgent createAgent(
            AgentLlmInvoker llmInvoker,
            AgentLoopExecutor loopExecutor,
            AgentToolPlanner toolPlanner,
            AgentProperties properties,
            ObjectMapper objectMapper,
            List<AgentTool> tools,
            AgentMetrics agentMetrics,
            AgentObservationService observationService,
            CharacterSoulService characterSoulService,
            ContextEngine contextEngine,
            TracePersistenceService tracePersistenceService,
            AgentDomainConfig domainConfig,
            SkillRegistry skillRegistry,
            SkillSelector skillSelector,
            SkillRequestPlanner skillRequestPlanner,
            SkillOutputValidator skillOutputValidator) {
        AgentBoundedCallExecutor boundedCallExecutor = new AgentBoundedCallExecutor();
        AgentMemoryCommitter memoryCommitter = new AgentMemoryCommitter(boundedCallExecutor);
        AgentTurnCompletion completion = new AgentTurnCompletion(objectMapper, memoryCommitter);
        AgentTurnTraceLifecycle traceLifecycle = new AgentTurnTraceLifecycle(
                objectMapper,
                agentMetrics,
                observationService,
                tracePersistenceService);
        AgentTurnPlanningService planningService = new AgentTurnPlanningService(
                tools,
                toolPlanner,
                skillRegistry,
                skillSelector,
                skillRequestPlanner);
        AgentContextPreparationService contextPreparationService =
                new AgentContextPreparationService(contextEngine, boundedCallExecutor);
        AgentTurnPreparationService preparationService = new AgentTurnPreparationService(
                planningService,
                contextPreparationService,
                new AgentPreparationSpanLifecycle(observationService));
        AgentFinalAnswerProtocol protocol = new AgentFinalAnswerProtocol(objectMapper, properties);
        AgentFinalAnswerRepairService repairService = new AgentFinalAnswerRepairService(
                llmInvoker,
                properties,
                observationService,
                boundedCallExecutor,
                protocol);
        AgentBoundaryResponseService boundaryResponseService = new AgentBoundaryResponseService(
                llmInvoker,
                properties,
                characterSoulService,
                observationService,
                completion);
        AgentFinalAnswerPromptFactory promptFactory =
                new AgentFinalAnswerPromptFactory(domainConfig, characterSoulService);
        AgentFinalCandidateGenerator candidateGenerator = new AgentFinalCandidateGenerator(
                llmInvoker,
                properties,
                observationService,
                promptFactory,
                protocol);
        AgentFinalAnswerValidationPipeline validationPipeline =
                new AgentFinalAnswerValidationPipeline(repairService, skillOutputValidator);
        AgentFinalAnswerService finalAnswerService = new AgentFinalAnswerService(
                candidateGenerator,
                validationPipeline,
                boundaryResponseService,
                completion,
                promptFactory);
        AgentTurnResponseService responseService = new AgentTurnResponseService(finalAnswerService);
        AgentTurnOrchestrator orchestrator = new AgentTurnOrchestrator(
                properties,
                domainConfig,
                traceLifecycle,
                preparationService,
                loopExecutor,
                responseService,
                completion);
        return new ChthollyAgent(properties, orchestrator);
    }
}
