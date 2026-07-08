package com.chtholly.agent.graph;

import java.time.Instant;

/**
 * Directed weighted edge between two knowledge entities.
 */
public record KnowledgeRelation(
        Long id,
        long sourceEntityId,
        long targetEntityId,
        KnowledgeRelationType relationType,
        double weight,
        String metadata,
        Instant createdAt
) {

    public KnowledgeRelation withId(Long id) {
        return new KnowledgeRelation(id, sourceEntityId, targetEntityId, relationType, weight, metadata, createdAt);
    }
}
