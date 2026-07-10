package com.chtholly.recommendation;

import com.chtholly.recommendation.model.InterestProfile;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void given_sakuraAndChinatsuStyle_when_cosineSimilarity_then_isLow() {
        // 治愈/生活/读书 vs 热门/趣事
        Map<String, Double> sakura = Map.of("治愈", 0.4, "生活", 0.35, "读书", 0.25);
        Map<String, Double> chinatsu = Map.of("热门", 0.55, "趣事", 0.45);

        assertThat(UserSimilarityService.cosineSimilarity(sakura, chinatsu))
                .isLessThan(UserSimilarityService.INTEREST_MATCH_THRESHOLD);
    }

    @Test
    void given_sharedHeavyTags_when_commonInterestTags_then_returnsOverlap() {
        UserInterestProfile profile = mock(UserInterestProfile.class);
        when(profile.buildProfile(1L)).thenReturn(new InterestProfile(
                1L, Map.of("治愈", 0.5, "读书", 0.3, "生活", 0.2), List.of(), Instant.now()));
        when(profile.buildProfile(2L)).thenReturn(new InterestProfile(
                2L, Map.of("治愈", 0.4, "读书", 0.4, "番剧", 0.2), List.of(), Instant.now()));

        UserSimilarityService service = new UserSimilarityService(profile, mock(StringRedisTemplate.class));
        assertThat(service.commonInterestTags(1L, 2L, 0.1))
                .containsExactly("治愈", "读书");
    }
}
