package com.chtholly.agent.anchor;

import java.util.List;

/**
 * Provides semantic knowledge snippets for the agent context.
 */
public interface KnowledgeService {

    /**
     * Returns semantic knowledge relevant to the current session.
     *
     * @param userId    Authenticated user ID.
     * @param sessionId Conversation session ID.
     * @return Relevant knowledge snippets.
     */
    List<String> getRelevantKnowledge(long userId, String sessionId);
}
