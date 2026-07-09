package com.chtholly.recommendation.model;

/**
 * 兴趣相近的用户（协同过滤候选）。
 */
public record SimilarUser(
        long userId,
        double similarity
) {
}
