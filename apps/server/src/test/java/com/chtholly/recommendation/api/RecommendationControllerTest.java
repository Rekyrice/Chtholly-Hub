package com.chtholly.recommendation.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.recommendation.RecommendationService;
import com.chtholly.recommendation.model.RecommendedPost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationControllerTest {

    @Mock
    private RecommendationService recommendationService;
    @Mock
    private JwtService jwtService;
    @InjectMocks
    private RecommendationController controller;

    @Test
    void given_authenticatedUser_when_recommend_then_returnsPersonalizedFlag() {
        Jwt jwt = mock(Jwt.class);
        when(jwtService.extractUserId(jwt)).thenReturn(42L);
        when(recommendationService.recommend(42L, 10)).thenReturn(
                new RecommendationService.RecommendationResult(
                        List.of(new RecommendedPost(1L, "测试文章", 0.8, "兴趣标签匹配")),
                        true));

        RecommendationListResponse response = controller.recommend(10, jwt);

        assertThat(response.personalized()).isTrue();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().postId()).isEqualTo(1L);
    }
}
