package com.chtholly.agent.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Post summary under a topic cluster.
 */
public record TopicPostResponse(
        Long id,
        String title,
        String description,
        Instant publishTime,
        List<String> tags
) {
}
