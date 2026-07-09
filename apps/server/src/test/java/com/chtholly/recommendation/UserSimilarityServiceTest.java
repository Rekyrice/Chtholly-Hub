package com.chtholly.recommendation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserSimilarityServiceTest {

    @Test
    void given_overlappingProfiles_when_cosineSimilarity_then_returnsExpectedScore() {
        Map<String, Double> left = Map.of("番剧", 0.6, "治愈", 0.4);
        Map<String, Double> right = Map.of("番剧", 0.5, "技术", 0.5);

        double similarity = UserSimilarityService.cosineSimilarity(left, right);

        assertThat(similarity).isGreaterThan(0.5).isLessThan(1.0);
    }

    @Test
    void given_disjointProfiles_when_cosineSimilarity_then_returnsZero() {
        Map<String, Double> left = Map.of("番剧", 1.0);
        Map<String, Double> right = Map.of("Java", 1.0);

        assertThat(UserSimilarityService.cosineSimilarity(left, right)).isEqualTo(0.0);
    }
}
