package com.chtholly.seed;

import java.time.Instant;
/**
 * Recommendation row generated from Bangumi subject data.
 */
public record BangumiRecommendationSeed(
        long bangumiId,
        String title,
        String titleCn,
        String coverUrl,
        double score,
        String chthollyReview,
        String tagsJson,
        Instant createdAt
) {
}
