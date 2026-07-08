package com.chtholly.agent.graph;

import java.util.List;

/**
 * Result of the entity and relation extraction pipeline.
 */
public record KnowledgeExtractionResult(
        List<ExtractedKnowledgeEntity> entities,
        List<ExtractedKnowledgeRelation> relations
) {
    public static KnowledgeExtractionResult empty() {
        return new KnowledgeExtractionResult(List.of(), List.of());
    }
}
