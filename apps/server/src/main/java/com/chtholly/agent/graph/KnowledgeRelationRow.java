package com.chtholly.agent.graph;

import java.time.Instant;

/**
 * MyBatis row for knowledge_relations.
 */
public record KnowledgeRelationRow(
        Long id,
        Long sourceEntityId,
        Long targetEntityId,
        String relationType,
        Double weight,
        String metadata,
        Instant createdAt
) {
}
