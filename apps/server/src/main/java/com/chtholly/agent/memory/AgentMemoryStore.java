package com.chtholly.agent.memory;

import com.chtholly.agent.config.AgentProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按 WebSocket sessionId 管理会话记忆。 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentMemoryStore {

    private final AgentProperties properties;
    private final Map<String, AgentConversationMemory> sessions = new ConcurrentHashMap<>();

    public AgentMemoryStore(AgentProperties properties) {
        this.properties = properties;
    }

    public AgentConversationMemory getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> new AgentConversationMemory(properties.getMemoryMaxTurns()));
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }
}
