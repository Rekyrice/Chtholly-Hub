package com.chtholly.recommendation;

import com.chtholly.content.ContentIntelligenceReader;
import com.chtholly.content.RelatedPostDto;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.recommendation.model.InterestProfile;
import com.chtholly.recommendation.model.RecommendedPost;
import com.chtholly.recommendation.model.SimilarUser;
import com.chtholly.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 融合标签画像、内容相似与协同过滤的推荐入口。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationService {

    private static final int RRF_K = 60;

    private final UserInterestProfile userInterestProfile;
    private final UserSimilarityService userSimilarityService;
    private final SearchService searchService;
    private final ContentIntelligenceReader contentUnderstandingService;
    private final PostMapper postMapper;

    /**
     * 个性化推荐；无画像时 fallback 到热门文章。
     */
    public RecommendationResult recommend(Long userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        if (userId == null || userId <= 0) {
            return hotFallback(null, safeLimit, false);
        }

        InterestProfile profile = userInterestProfile.buildProfile(userId);
        if (!profile.hasSignal()) {
            return hotFallback(userId, safeLimit, false);
        }

        Set<Long> exclude = new HashSet<>(profile.interactedPostIds());
        Map<Long, RankedCandidate> fused = new LinkedHashMap<>();

        mergeRankedList(fused, toPostIds(searchService.recommendByInterest(
                profile.tagWeights(), exclude, safeLimit * 2, userId)), 1.0, "兴趣标签匹配");
        mergeRankedList(fused, contentBasedCandidates(profile, exclude, userId, safeLimit), 0.8, "内容相似");
        if (userSimilarityService.collaborativeFilteringEnabled()) {
            mergeRankedList(fused, collaborativeCandidates(userId, profile, exclude, safeLimit), 0.6, "相似用户喜欢");
        }

        List<RecommendedPost> ranked = finalizeRanking(fused, safeLimit);
        return new RecommendationResult(ranked, true);
    }

    private RecommendationResult hotFallback(Long userId, int limit, boolean personalized) {
        List<FeedItemResponse> hot = searchService.recommendHot(Set.of(), limit, userId);
        List<RecommendedPost> items = hot.stream()
                .map(item -> new RecommendedPost(
                        parsePostId(item.id()),
                        item.title(),
                        0.0,
                        "热门推荐"))
                .toList();
        return new RecommendationResult(items, personalized);
    }

    private List<Long> contentBasedCandidates(InterestProfile profile,
                                              Set<Long> exclude,
                                              Long userId,
                                              int limit) {
        List<Long> seedPostIds = profile.interactedPostIds();
        if (seedPostIds.isEmpty()) {
            return List.of();
        }
        Long anchor = seedPostIds.getFirst();
        Set<Long> merged = new LinkedHashSet<>();
        merged.addAll(toPostIds(searchService.recommendSimilarToPost(anchor, exclude, limit, userId)));
        try {
            for (RelatedPostDto related : contentUnderstandingService.getRelatedPosts(anchor)) {
                if (related.id() != null && !exclude.contains(related.id())) {
                    merged.add(related.id());
                }
            }
        } catch (Exception e) {
            log.debug("内容理解相关推荐失败 anchor={}: {}", anchor, e.getMessage());
        }
        return new ArrayList<>(merged);
    }

    private List<Long> collaborativeCandidates(long userId,
                                               InterestProfile profile,
                                               Set<Long> exclude,
                                               int limit) {
        List<Long> result = new ArrayList<>();
        for (SimilarUser similar : userSimilarityService.findSimilarUsers(userId, 5)) {
            InterestProfile peer = userInterestProfile.buildProfile(similar.userId());
            for (Long postId : peer.interactedPostIds()) {
                if (exclude.contains(postId) || postId == null) {
                    continue;
                }
                result.add(postId);
                if (result.size() >= limit) {
                    return result;
                }
            }
        }
        return result;
    }

    private void mergeRankedList(Map<Long, RankedCandidate> fused,
                                 List<Long> postIds,
                                 double weight,
                                 String reason) {
        for (int i = 0; i < postIds.size(); i++) {
            Long postId = postIds.get(i);
            if (postId == null || postId <= 0) {
                continue;
            }
            double rrf = weight / (RRF_K + i + 1);
            fused.compute(postId, (id, existing) -> {
                if (existing == null) {
                    return new RankedCandidate(id, rrf, reason);
                }
                return new RankedCandidate(id, existing.score() + rrf, mergeReason(existing.reason(), reason));
            });
        }
    }

    private List<RecommendedPost> finalizeRanking(Map<Long, RankedCandidate> fused, int limit) {
        List<RankedCandidate> sorted = fused.values().stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .toList();
        Map<Long, Post> posts = loadPosts(sorted.stream().map(RankedCandidate::postId).toList());
        List<RecommendedPost> result = new ArrayList<>(sorted.size());
        for (RankedCandidate candidate : sorted) {
            Post post = posts.get(candidate.postId());
            String title = post == null ? "文章 " + candidate.postId() : post.getTitle();
            result.add(new RecommendedPost(candidate.postId(), title, candidate.score(), candidate.reason()));
        }
        return result;
    }

    private Map<Long, Post> loadPosts(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Post> map = new HashMap<>();
        for (Post post : postMapper.findByIds(ids)) {
            if (post != null && post.getId() != null) {
                map.put(post.getId(), post);
            }
        }
        return map;
    }

    private static List<Long> toPostIds(List<FeedItemResponse> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(items.size());
        for (FeedItemResponse item : items) {
            long id = parsePostId(item.id());
            if (id > 0) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static long parsePostId(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String mergeReason(String left, String right) {
        if (left == null || left.isBlank()) {
            return right;
        }
        if (left.contains(right)) {
            return left;
        }
        return left + " + " + right;
    }

    private record RankedCandidate(long postId, double score, String reason) {
    }

    public record RecommendationResult(List<RecommendedPost> items, boolean personalized) {
    }
}
