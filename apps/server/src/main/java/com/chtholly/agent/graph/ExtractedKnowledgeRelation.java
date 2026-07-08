package com.chtholly.agent.graph;

/**
 * Relation extracted from free text before entity IDs are known.
 */
public record ExtractedKnowledgeRelation(
        String sourceName,
        String targetName,
        KnowledgeRelationType relationType,
        double weight
) {
}
