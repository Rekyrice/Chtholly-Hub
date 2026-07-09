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

    private List<Long> listProfileUserIds() {
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
                    // skip
                }
            }
        }
        return ids;
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
