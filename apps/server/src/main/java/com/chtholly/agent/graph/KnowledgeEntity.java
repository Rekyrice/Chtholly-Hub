package com.chtholly.agent.graph;

import java.time.Instant;
import java.util.List;

/**
 * Graph entity node.
 */
public record KnowledgeEntity(
        Long id,
        String name,
        KnowledgeEntityType type,
        String description,
        List<String> aliases,
        String embedding,
        String metadata,
        Instant createdAt,
        Instant updatedAt
) {

    public KnowledgeEntity withDescription(String description) {
        return new KnowledgeEntity(id, name, type, description, aliases, embedding, metadata, createdAt, updatedAt);
    }

    public KnowledgeEntity withAliases(List<String> aliases) {
        return new KnowledgeEntity(id, name, type, description, aliases == null ? List.of() : List.copyOf(aliases), embedding, metadata, createdAt, updatedAt);
    }

    public KnowledgeEntity withEmbedding(String embedding) {
        return new KnowledgeEntity(id, name, type, description, aliases, embedding, metadata, createdAt, updatedAt);
    }

    public KnowledgeEntity withMetadata(String metadata) {
        return new KnowledgeEntity(id, name, type, description, aliases, embedding, metadata, createdAt, updatedAt);
    }

    public KnowledgeEntity withUpdatedAt(Instant updatedAt) {
        return new KnowledgeEntity(id, name, type, description, aliases, embedding, metadata, createdAt, updatedAt);
    }
}
