package com.chtholly.agent.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QualityCriteriaTest {

    @Test
    void predefinedCriteriaExposeWeightsAndThresholdsForKnownScenarios() {
        QualityCriteria comment = QualityCriteria.commentQuality();
        QualityCriteria article = QualityCriteria.articleQuality();
        QualityCriteria curation = QualityCriteria.curationQuality();

        assertThat(comment.minScore()).isEqualTo(3.5);
        assertThat(comment.maxAttempts()).isEqualTo(2);
        assertThat(comment.dimensions()).extracting(QualityCriteria.Dimension::name)
                .containsExactly("内容相关性", "人设一致性", "趣味性", "原创性");
        assertThat(comment.dimensions()).extracting(QualityCriteria.Dimension::weight)
                .containsExactly(0.3, 0.3, 0.2, 0.2);

        assertThat(article.minScore()).isEqualTo(3.0);
        assertThat(article.dimensions()).extracting(QualityCriteria.Dimension::name)
                .containsExactly("内容深度", "可读性", "有价值", "真实感");

        assertThat(curation.minScore()).isEqualTo(3.5);
        assertThat(curation.dimensions()).extracting(QualityCriteria.Dimension::name)
                .containsExactly("推荐价值", "多样性", "趣味性");
    }
}
