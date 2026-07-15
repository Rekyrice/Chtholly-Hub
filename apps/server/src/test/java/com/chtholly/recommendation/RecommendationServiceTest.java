package com.chtholly.recommendation;

import com.chtholly.content.ContentIntelligenceReader;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.recommendation.model.InterestProfile;
import com.chtholly.search.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private UserInterestProfile userInterestProfile;
    @Mock
    private UserSimilarityService userSimilarityService;
    @Mock
    private SearchService searchService;
    @Mock
    private ContentIntelligenceReader contentUnderstandingService;
    @Mock
    private PostMapper postMapper;

    private RecommendationService recommendationService;

    @BeforeEach
    void setUp() {
        recommendationService = new RecommendationService(
                userInterestProfile,
                userSimilarityService,
                searchService,
                contentUnderstandingService,
                postMapper);
    }

    @Test
    void given_noUser_when_recommend_then_returnsHotFallback() {
        when(searchService.recommendHot(Set.of(), 5, null)).thenReturn(List.of(
                feedItem("11", "热门文章 A"),
                feedItem("12", "热门文章 B")));

        RecommendationService.RecommendationResult result = recommendationService.recommend(null, 5);

        assertThat(result.personalized()).isFalse();
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().getFirst().reason()).isEqualTo("热门推荐");
    }

    @Test
    void given_animeProfile_when_recommend_then_mergesTagAndContentSignals() {
        InterestProfile animeProfile = new InterestProfile(
                100L,
                Map.of("番剧", 0.6, "治愈", 0.4),
                List.of(501L),
                Instant.now());
        when(userInterestProfile.buildProfile(100L)).thenReturn(animeProfile);
        when(userSimilarityService.collaborativeFilteringEnabled()).thenReturn(false);
        when(searchService.recommendByInterest(eq(animeProfile.tagWeights()), any(), anyInt(), eq(100L)))
                .thenReturn(List.of(feedItem("601", "夏目友人帐观后感")));
        when(searchService.recommendSimilarToPost(eq(501L), any(), anyInt(), eq(100L)))
                .thenReturn(List.of(feedItem("602", "芙莉莲推荐")));
        when(contentUnderstandingService.getRelatedPosts(501L)).thenReturn(List.of());
        Post post601 = new Post();
        post601.setId(601L);
        post601.setTitle("夏目友人帐观后感");
        Post post602 = new Post();
        post602.setId(602L);
        post602.setTitle("芙莉莲推荐");
        when(postMapper.findByIds(any())).thenReturn(List.of(post601, post602));

        RecommendationService.RecommendationResult result = recommendationService.recommend(100L, 5);

        assertThat(result.personalized()).isTrue();
        assertThat(result.items()).extracting("title")
                .contains("夏目友人帐观后感", "芙莉莲推荐");
    }

    @Test
    void given_seedAnimeCriticVsNightCoderProfiles_when_cosine_then_animeCloserToAnime() {
        Map<String, Double> animeCritic = Map.of("番剧", 0.5, "治愈", 0.3, "观后感", 0.2);
        Map<String, Double> nightCoder = Map.of("Java", 0.4, "后端", 0.35, "技术", 0.25);
        Map<String, Double> peerAnime = Map.of("番剧", 0.6, "日常系", 0.4);

        double animeToPeer = UserSimilarityService.cosineSimilarity(animeCritic, peerAnime);
        double coderToPeer = UserSimilarityService.cosineSimilarity(nightCoder, peerAnime);

        assertThat(animeToPeer).isGreaterThan(coderToPeer);
    }

    private static FeedItemResponse feedItem(String id, String title) {
        return new FeedItemResponse(
                id, "slug-" + id, title, "desc", null,
                List.of("tag"), null, "author", null,
                1L, 0L, false, false, false);
    }
}
