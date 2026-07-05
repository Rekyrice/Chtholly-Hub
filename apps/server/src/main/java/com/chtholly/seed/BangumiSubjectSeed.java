package com.chtholly.seed;

import java.util.List;

/**
 * Minimal Bangumi subject data used by the recommendation seeder.
 */
public record BangumiSubjectSeed(
        long bangumiId,
        String title,
        String titleCn,
        String coverUrl,
        double score,
        String summary,
        List<String> tags
) {
}
