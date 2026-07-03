package com.chtholly.agent.anchor;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Placeholder semantic anchor until the dedicated knowledge base is available.
 */
@Service
public class NoopKnowledgeService implements KnowledgeService {

    /**
     * Returns no semantic snippets for now.
     *
     * @param userId    Authenticated user ID.
     * @param sessionId Conversation session ID.
     * @return Empty semantic context.
     */
    @Override
    public List<String> getRelevantKnowledge(long userId, String sessionId) {
        return List.of();
    }
}
