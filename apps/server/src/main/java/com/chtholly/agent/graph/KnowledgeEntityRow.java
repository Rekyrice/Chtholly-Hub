package com.chtholly.agent.graph;

import java.time.Instant;

/**
 * MyBatis row for knowledge_entities.
 */
public record KnowledgeEntityRow(
        Long id,
        String name,
        String type,
        String description,
        String aliases,
        String embedding,
        String metadata,
        Instant createdAt,
        Instant updatedAt
) {
}
