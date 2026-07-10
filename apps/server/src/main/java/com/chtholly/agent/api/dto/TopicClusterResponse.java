package com.chtholly.agent.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Public topic cluster summary for Hub / discovery surfaces.
 */
public record TopicClusterResponse(
        String topicName,
        String summary,
        int size,
        List<String> keyEntities,
        Instant clusteredAt
) {
}
