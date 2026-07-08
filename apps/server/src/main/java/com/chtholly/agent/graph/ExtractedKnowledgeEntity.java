package com.chtholly.agent.graph;

import java.util.List;

/**
 * Entity extracted from free text before persistence.
 */
public record ExtractedKnowledgeEntity(
        String name,
        KnowledgeEntityType type,
        String description,
        List<String> aliases
) {
}
