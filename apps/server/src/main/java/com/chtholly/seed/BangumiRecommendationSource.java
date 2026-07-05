package com.chtholly.seed;

import java.util.List;

/**
 * Source of Bangumi subjects for recommendation seeding.
 */
@FunctionalInterface
public interface BangumiRecommendationSource {
    List<BangumiSubjectSeed> fetchTopAnime(int limit);
}
