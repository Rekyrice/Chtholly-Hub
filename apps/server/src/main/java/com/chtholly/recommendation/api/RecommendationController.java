package com.chtholly.recommendation.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.recommendation.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个性化推荐 API。
 */
@Tag(name = "推荐", description = "基于用户行为的个性化推荐")
@RestController
@RequestMapping("/api/v1/recommendations")
@Validated
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final JwtService jwtService;

    @Operation(summary = "获取个性化推荐列表")
    @GetMapping
    public RecommendationListResponse recommend(
            @RequestParam(value = "limit", required = false, defaultValue = "10") @Min(1) @Max(50) int limit,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt == null ? null : jwtService.extractUserId(jwt);
        RecommendationService.RecommendationResult result = recommendationService.recommend(userId, limit);
        return RecommendationListResponse.from(result.items(), result.personalized());
    }
}
