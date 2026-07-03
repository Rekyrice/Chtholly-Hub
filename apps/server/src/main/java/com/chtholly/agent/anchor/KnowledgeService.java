package com.chtholly.agent.anchor;

import com.chtholly.agent.search.SearchResult;

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

    /**
     * Searches entity knowledge for a user query.
     *
     * @param query Query text.
     * @param topK  Maximum result count.
     * @return Entity search results.
     */
    default List<SearchResult> searchEntities(String query, int topK) {
        return List.of();
    }
}
