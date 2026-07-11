package com.chtholly.content;

import java.time.Instant;
import java.util.List;

/**
 * Content understanding result for one post.
 *
 * @param entities       extracted entities
 * @param summary        Chtholly-style summary
 * @param relatedPostIds related post IDs
 * @param analyzedAt     analysis timestamp
 */
public record ContentAnalysis(
        List<Entity> entities,
        String summary,
        List<Long> relatedPostIds,
        Instant analyzedAt
) {
}
