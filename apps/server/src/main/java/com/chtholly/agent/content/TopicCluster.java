package com.chtholly.agent.content;

import java.time.Instant;
import java.util.List;

/**
 * Semantic topic cluster discovered from recent community posts.
 *
 * @param topicName   LLM-extracted topic label
 * @param summary     one-line discussion summary
 * @param postIds     member post IDs
 * @param size        cluster size (heat signal)
 * @param keyEntities notable entities in the cluster
 * @param clusteredAt clustering timestamp
 */
public record TopicCluster(
        String topicName,
        String summary,
        List<Long> postIds,
        int size,
        List<String> keyEntities,
        Instant clusteredAt
) {
}
