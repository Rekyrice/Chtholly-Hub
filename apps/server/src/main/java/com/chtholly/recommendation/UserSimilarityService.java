package com.chtholly.recommendation;

import com.chtholly.recommendation.model.InterestProfile;
import com.chtholly.recommendation.model.SimilarUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于兴趣画像余弦相似度的 User-Based 协同过滤。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserSimilarityService {

    private static final String SIMILAR_KEY_PREFIX = "user:similar:";
    /** 搭桥推送默认相似度阈值。 */
    public static final double INTEREST_MATCH_THRESHOLD = 0.6;
    /** 共同兴趣标签的最低权重。 */
    public static final double COMMON_TAG_MIN_WEIGHT = 0.1;

    private final UserInterestProfile userInterestProfile;
    private final StringRedisTemplate redis;

    @Value("${recommendation.cf.min-users:10}")
    private int minUsersForCollaborative;

    @Value("${recommendation.cf.similarity-ttl-hours:24}")
    private int similarityTtlHours;

    public boolean collaborativeFilteringEnabled() {
        return countInterestProfiles() >= minUsersForCollaborative;
    }

    /**
     * 找兴趣最相近的用户，结果缓存于 Redis Sorted Set。
     */
    public List<SimilarUser> findSimilarUsers(long userId, int topK) {
        String cacheKey = similarKey(userId);
        Set<String> cached = redis.opsForZSet().reverseRange(cacheKey, 0, topK - 1);
        if (cached != null && !cached.isEmpty()) {
            List<SimilarUser> result = new ArrayList<>(cached.size());
            for (String member : cached) {
                Double score = redis.opsForZSet().score(cacheKey, member);
                if (score != null) {
                    result.add(new SimilarUser(Long.parseLong(member), score));
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        InterestProfile source = userInterestProfile.buildProfile(userId);
        List<SimilarUser> computed = computeSimilarUsers(source, topK);
        if (!computed.isEmpty()) {
            String key = similarKey(userId);
            computed.forEach(user ->
                    redis.opsForZSet().add(key, String.valueOf(user.userId()), user.similarity()));
            redis.expire(key, Duration.ofHours(similarityTtlHours));
        }
        return computed;
    }

    /**
     * Lists all user IDs that currently have an interest profile in Redis.
     *
     * @return profile user IDs discovered via SCAN
     */
    public List<Long> listProfileUserIds() {
        List<Long> ids = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(UserInterestProfile.INTEREST_KEY_PREFIX + "*")
                .count(50)
                .build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String suffix = key.substring(UserInterestProfile.INTEREST_KEY_PREFIX.length());
                try {
                    ids.add(Long.parseLong(suffix));
                } catch (NumberFormatException ignored) {
                    // skip malformed keys
                }
            }
        }
        return ids;
    }

    /**
     * Tags where both users have weight above {@code minWeight}, ranked by shared strength.
     *
     * @param userA     first user
     * @param userB     second user
     * @param minWeight minimum weight on both sides
     * @return shared interest tags (may be empty)
     */
    public List<String> commonInterestTags(long userA, long userB, double minWeight) {
        InterestProfile left = userInterestProfile.buildProfile(userA);
        InterestProfile right = userInterestProfile.buildProfile(userB);
        if (!left.hasSignal() || !right.hasSignal()) {
            return List.of();
        }
        Map<String, Double> leftWeights = left.tagWeights() == null ? Map.of() : left.tagWeights();
        Map<String, Double> rightWeights = right.tagWeights() == null ? Map.of() : right.tagWeights();
        List<Map.Entry<String, Double>> shared = new ArrayList<>();
        for (Map.Entry<String, Double> entry : leftWeights.entrySet()) {
            double lw = entry.getValue() == null ? 0.0 : entry.getValue();
            double rw = rightWeights.getOrDefault(entry.getKey(), 0.0);
            if (lw > minWeight && rw > minWeight) {
                shared.add(Map.entry(entry.getKey(), Math.min(lw, rw)));
            }
        }
        shared.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        return shared.stream().map(Map.Entry::getKey).toList();
    }

    private List<SimilarUser> computeSimilarUsers(InterestProfile source, int topK) {
        if (!source.hasSignal()) {
            return List.of();
        }
        List<SimilarUser> ranked = new ArrayList<>();
        for (Long candidateId : listProfileUserIds()) {
            if (candidateId == source.userId()) {
                continue;
            }
            InterestProfile candidate = userInterestProfile.buildProfile(candidateId);
            double similarity = cosineSimilarity(source.tagWeights(), candidate.tagWeights());
            if (similarity > 0) {
                ranked.add(new SimilarUser(candidateId, similarity));
            }
        }
        ranked.sort(Comparator.comparingDouble(SimilarUser::similarity).reversed());
        return ranked.stream().limit(topK).toList();
    }

    private int countInterestProfiles() {
        return listProfileUserIds().size();
    }

    static double cosineSimilarity(Map<String, Double> left, Map<String, Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        Map<String, Double> merged = new HashMap<>(left);
        right.forEach((key, value) -> merged.merge(key, value, (a, b) -> b));
        for (Map.Entry<String, Double> entry : merged.entrySet()) {
            double lv = left.getOrDefault(entry.getKey(), 0.0);
            double rv = right.getOrDefault(entry.getKey(), 0.0);
            dot += lv * rv;
            leftNorm += lv * lv;
            rightNorm += rv * rv;
        }
        if (leftNorm <= 0 || rightNorm <= 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    static String similarKey(long userId) {
        return SIMILAR_KEY_PREFIX + userId;
    }
}
