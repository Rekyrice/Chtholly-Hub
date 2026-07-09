package com.chtholly.recommendation.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 用户兴趣画像：标签权重 + 已交互文章。
 */
public record InterestProfile(
        long userId,
        Map<String, Double> tagWeights,
        List<Long> interactedPostIds,
        Instant updatedAt
) {

    public boolean hasSignal() {
        return (tagWeights != null && !tagWeights.isEmpty())
                || (interactedPostIds != null && !interactedPostIds.isEmpty());
    }
}
