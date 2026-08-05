package com.chtholly.agent;

import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.runtime.AgentTurnControl;
import com.chtholly.agent.runtime.AgentTurnOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
