package com.chtholly.recommendation;

import com.chtholly.counter.service.CounterService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.post.model.PostFeedRow;
import com.chtholly.recommendation.model.InterestProfile;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从用户行为构建兴趣画像，缓存于 Redis。
 */
@Component
@Slf4j
public class UserInterestProfile {

    public static final String INTEREST_KEY_PREFIX = "user:interest:";
    static final String INTERACTION_KEY_PREFIX = "user:interactions:";

    private static final TypeReference<Map<String, Double>> TAG_WEIGHTS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final CounterService counterService;
    private final TagJsonParser tagJsonParser;
    private final ObjectMapper objectMapper;

    @Value("${recommendation.profile.ttl-days:30}")
    private int profileTtlDays;

    @Value("${recommendation.profile.bootstrap-post-limit:300}")
    private int bootstrapPostLimit;

    public UserInterestProfile(StringRedisTemplate redis,
                               PostMapper postMapper,
                               UserMapper userMapper,
                               CounterService counterService,
                               TagJsonParser tagJsonParser,
                               ObjectMapper objectMapper) {
        this.redis = redis;
        this.postMapper = postMapper;
        this.userMapper = userMapper;
        this.counterService = counterService;
        this.tagJsonParser = tagJsonParser;
        this.objectMapper = objectMapper;
    }

    /**
     * 构建或读取缓存的用户兴趣画像。
     */
    public InterestProfile buildProfile(long userId) {
        InterestProfile cached = loadCached(userId);
        if (cached != null && cached.updatedAt().isAfter(Instant.now().minus(Duration.ofHours(1)))) {
            return cached;
        }
        return rebuildProfile(userId);
    }

    /**
     * 点赞/收藏/浏览后增量更新画像。
     */
    public void updateProfile(long userId, long postId, String action) {
        recordInteraction(userId, postId, actionWeight(action));
        Post post = postMapper.findById(postId);
        if (post == null) {
            return;
        }
        InterestProfile current = loadCached(userId);
        Map<String, Double> weights = current == null
                ? new HashMap<>()
                : new HashMap<>(current.tagWeights());
        List<Long> interacted = current == null
                ? new ArrayList<>()
                : new ArrayList<>(current.interactedPostIds());
        mergePostTags(weights, tagJsonParser.parse(post.getTags()), actionWeight(action));
        if (!interacted.contains(postId)) {
            interacted.add(postId);
        }
        saveProfile(new InterestProfile(userId, normalize(weights), List.copyOf(interacted), Instant.now()));
    }

    /**
     * 供 Hub 使用的兴趣标签 CSV。
     */
    public String interestTagsCsv(long userId) {
        InterestProfile profile = buildProfile(userId);
        if (!profile.hasSignal()) {
            return "";
        }
        return profile.tagWeights().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    InterestProfile rebuildProfile(long userId) {
        Map<String, Double> weights = new HashMap<>();
        Set<Long> interacted = new LinkedHashSet<>(loadInteractionPostIds(userId));

        if (interacted.isEmpty()) {
            interacted.addAll(bootstrapInteractionsFromBitmap(userId));
        }

        List<Post> posts = interacted.isEmpty()
                ? List.of()
                : postMapper.findByIds(new ArrayList<>(interacted));
        for (Post post : posts) {
            mergePostTags(weights, tagJsonParser.parse(post.getTags()), 1.0);
        }

        User user = userMapper.findById(userId);
        if (user != null && weights.isEmpty()) {
            mergePostTags(weights, tagJsonParser.parse(user.getTagsJson()), 0.5);
        }

        InterestProfile profile = new InterestProfile(
                userId,
                normalize(weights),
                List.copyOf(interacted),
                Instant.now());
        saveProfile(profile);
        return profile;
    }

    private InterestProfile loadCached(long userId) {
        Map<Object, Object> entries = redis.opsForHash().entries(interestKey(userId));
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        try {
            Map<String, Double> tagWeights = objectMapper.readValue(
                    String.valueOf(entries.get("tagWeights")), TAG_WEIGHTS_TYPE);
            List<Long> interacted = objectMapper.readValue(
                    String.valueOf(entries.get("interactedPostIds")), LONG_LIST_TYPE);
            Instant updatedAt = Instant.parse(String.valueOf(entries.get("updatedAt")));
            return new InterestProfile(userId, tagWeights, interacted, updatedAt);
        } catch (Exception e) {
            log.debug("兴趣画像缓存读取失败 userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private void saveProfile(InterestProfile profile) {
        try {
            Map<String, String> hash = Map.of(
                    "tagWeights", objectMapper.writeValueAsString(profile.tagWeights()),
                    "interactedPostIds", objectMapper.writeValueAsString(profile.interactedPostIds()),
                    "updatedAt", profile.updatedAt().toString());
            String key = interestKey(profile.userId());
            redis.opsForHash().putAll(key, hash);
            redis.expire(key, Duration.ofDays(profileTtlDays));
        } catch (Exception e) {
            log.warn("兴趣画像写入 Redis 失败 userId={}: {}", profile.userId(), e.getMessage());
        }
    }

    private void recordInteraction(long userId, long postId, double weight) {
        String key = interactionKey(userId);
        redis.opsForZSet().add(key, String.valueOf(postId), Instant.now().toEpochMilli() + weight);
        redis.expire(key, Duration.ofDays(profileTtlDays));
    }

    private List<Long> loadInteractionPostIds(long userId) {
        var tuples = redis.opsForZSet().reverseRangeWithScores(interactionKey(userId), 0, 199);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(profileTtlDays));
        List<Long> ids = new ArrayList<>();
        for (var tuple : tuples) {
            if (tuple.getScore() == null || tuple.getScore() < cutoff.toEpochMilli()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(tuple.getValue()));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return ids;
    }

    private Set<Long> bootstrapInteractionsFromBitmap(long userId) {
        Instant since = Instant.now().minus(Duration.ofDays(profileTtlDays));
        List<PostFeedRow> recent = postMapper.listRecentPublicSince(since, bootstrapPostLimit);
        if (recent.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = recent.stream().map(PostFeedRow::getId).toList();
        Map<Long, Boolean> liked = counterService.batchIsLiked(userId, ids);
        Map<Long, Boolean> faved = counterService.batchIsFaved(userId, ids);
        Set<Long> matched = new LinkedHashSet<>();
        for (Long id : ids) {
            if (Boolean.TRUE.equals(liked.get(id)) || Boolean.TRUE.equals(faved.get(id))) {
                matched.add(id);
            }
        }
        return matched;
    }

    private static void mergePostTags(Map<String, Double> weights, List<String> tags, double increment) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        for (String tag : tags) {
            weights.merge(tag, increment, Double::sum);
        }
    }

    private static Map<String, Double> normalize(Map<String, Double> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        double sum = raw.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) {
            return Map.of();
        }
        Map<String, Double> normalized = new LinkedHashMap<>();
        raw.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(entry -> normalized.put(entry.getKey(), entry.getValue() / sum));
        return Map.copyOf(normalized);
    }

    private static double actionWeight(String action) {
        if ("fav".equalsIgnoreCase(action) || "favorite".equalsIgnoreCase(action)) {
            return 1.5;
        }
        if ("view".equalsIgnoreCase(action)) {
            return 0.3;
        }
        return 1.0;
    }

    static String interestKey(long userId) {
        return INTEREST_KEY_PREFIX + userId;
    }

    static String interactionKey(long userId) {
        return INTERACTION_KEY_PREFIX + userId;
    }
}
