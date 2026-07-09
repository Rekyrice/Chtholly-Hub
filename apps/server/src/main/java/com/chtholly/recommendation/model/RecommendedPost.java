package com.chtholly.recommendation.model;

/**
 * 单条推荐结果。
 */
public record RecommendedPost(
        long postId,
        String title,
        double score,
        String reason
) {
}
