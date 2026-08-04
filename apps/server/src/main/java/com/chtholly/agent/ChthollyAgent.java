package com.chtholly.agent;

import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.context.ContextEngine;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.observability.AgentMetrics;
import com.chtholly.agent.observability.AgentObservationService;
import com.chtholly.agent.runtime.AgentLlmInvoker;
import com.chtholly.agent.runtime.AgentLoopExecutor;
import com.chtholly.agent.runtime.AgentToolPlanner;
import com.chtholly.agent.runtime.AgentTurnControl;
import com.chtholly.agent.runtime.AgentTurnOrchestrator;
import com.chtholly.agent.skill.SkillOutputValidator;
import com.chtholly.agent.skill.SkillRequestPlanner;
import com.chtholly.agent.skill.SkillRegistry;
import com.chtholly.agent.skill.SkillSelector;
import com.chtholly.agent.trace.TracePersistenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/** Public compatibility facade that normalizes requests into one agent turn command. */
@Service
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class ChthollyAgent {

    private final AgentProperties properties;
    private final AgentTurnOrchestrator orchestrator;

    /** Creates the Spring-managed agent facade. */
    @Autowired
    public ChthollyAgent(AgentProperties properties, AgentTurnOrchestrator orchestrator) {
        this.properties = properties;
        this.orchestrator = orchestrator;
    }

    /** Creates the legacy direct-construction facade retained for compatibility. */
    public ChthollyAgent(
            AgentLlmInvoker llmInvoker, AgentLoopExecutor loopExecutor,
            AgentToolPlanner toolPlanner, AgentProperties properties, ObjectMapper objectMapper,
            List<AgentTool> tools, AgentMetrics agentMetrics,
            AgentObservationService observationService,
            CharacterSoulService characterSoulService, ContextEngine contextEngine,
            TracePersistenceService tracePersistenceService, AgentDomainConfig domainConfig,
            SkillRegistry skillRegistry, SkillSelector skillSelector,
            SkillRequestPlanner skillRequestPlanner, SkillOutputValidator skillOutputValidator) {
        this(properties, AgentLegacyComposition.create(
                llmInvoker, loopExecutor, toolPlanner, properties, objectMapper, tools,
                agentMetrics, observationService, characterSoulService, contextEngine,
                tracePersistenceService, domainConfig, skillRegistry, skillSelector,
                skillRequestPlanner, skillOutputValidator));
    }

    /** Runs one turn without explicit session or page context. */
    public void run(
            String question, long userId, AgentConversationMemory memory,
            Consumer<AgentEvent> sink) {
        run(question, userId, memory, null, null, sink);
    }

    /** Runs one turn with a trace session identifier. */
    public void run(
            String question, long userId, AgentConversationMemory memory,
            String sessionId, Consumer<AgentEvent> sink) {
        run(question, userId, memory, sessionId, null, sink);
    }

    /** Runs one turn with session and page context. */
    public void run(
            String question, long userId, AgentConversationMemory memory,
            String sessionId, String pageContext, Consumer<AgentEvent> sink) {
        run(question, userId, memory, sessionId, pageContext, null, sink);
    }

    /** Runs one turn with an optional server-validated product task type. */
    public void run(
            String question, long userId, AgentConversationMemory memory,
            String sessionId, String pageContext, String taskType,
            Consumer<AgentEvent> sink) {
        AgentTurnControl control = AgentTurnControl.standalone(
                sessionId, Duration.ofSeconds(Math.max(1, properties.getTurnTimeoutSeconds())));
        dispatch(question, userId, memory, control, sessionId, pageContext, taskType, sink);
    }

    /** Runs one WebSocket turn with canonical request identity and cancellation state. */
    public void run(
            String question, long userId, AgentConversationMemory memory,
            AgentTurnControl turnControl, String pageContext, String taskType,
            Consumer<AgentEvent> sink) {
        AgentTurnControl control = turnControl == null
                ? AgentTurnControl.standalone(
                        null,
                        Duration.ofSeconds(Math.max(1, properties.getTurnTimeoutSeconds())))
                : turnControl;
        dispatch(
                question, userId, memory, control, control.chatSessionId(),
                pageContext, taskType, sink);
    }

    private void dispatch(
            String question, long userId, AgentConversationMemory memory,
            AgentTurnControl control, String sessionId, String pageContext, String taskType,
            Consumer<AgentEvent> sink) {
        orchestrator.run(new AgentTurnOrchestrator.Command(
                question, userId, memory, control, sessionId, pageContext, taskType, sink));
    }
}
