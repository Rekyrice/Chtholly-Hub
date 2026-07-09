package com.chtholly.agent.quality;

import java.util.List;

/**
 * Configurable quality evaluation criteria.
 */
public record QualityCriteria(
        List<Dimension> dimensions,
        double minScore,
        int maxAttempts
) {

    /**
     * Weighted quality dimension.
     */
    public record Dimension(String name, double weight) {
    }

    /**
     * Criteria for generated community comments.
     *
     * @return comment quality criteria.
     */
    public static QualityCriteria commentQuality() {
        return new QualityCriteria(
                List.of(
                        new Dimension("内容相关性", 0.3),
                        new Dimension("人设一致性", 0.3),
                        new Dimension("趣味性", 0.2),
                        new Dimension("原创性", 0.2)),
                3.5,
                2);
    }

    /**
     * Criteria for seed or user articles.
     *
     * @return article quality criteria.
     */
    public static QualityCriteria articleQuality() {
        return new QualityCriteria(
                List.of(
                        new Dimension("内容深度", 0.3),
                        new Dimension("可读性", 0.25),
                        new Dimension("有价值", 0.25),
                        new Dimension("真实感", 0.2)),
                3.0,
                1);
    }

    /**
     * Criteria for curated recommendation collections.
     *
     * @return curation quality criteria.
     */
    public static QualityCriteria curationQuality() {
        return new QualityCriteria(
                List.of(
                        new Dimension("推荐价值", 0.4),
                        new Dimension("多样性", 0.3),
                        new Dimension("趣味性", 0.3)),
                3.5,
                1);
    }
}
