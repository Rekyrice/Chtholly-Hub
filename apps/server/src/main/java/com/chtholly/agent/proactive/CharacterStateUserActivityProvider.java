package com.chtholly.agent.proactive;

import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.post.service.PostService;
import com.chtholly.recommendation.UserInterestProfile;
import com.chtholly.recommendation.model.InterestProfile;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 CharacterState Redis 索引与兴趣画像的用户活动数据源。
 */
@Component
@RequiredArgsConstructor
public class CharacterStateUserActivityProvider implements ProactiveTriggerEngine.UserActivityProvider {

    private static final Duration RETURN_BRIEFING_THRESHOLD = Duration.ofDays(7);
    private static final Duration ACTIVE_WINDOW = Duration.ofDays(7);
    private static final double TAG_MATCH_MIN_WEIGHT = 0.05;

    private static final String INTEREST_KEY_PREFIX = "user:interest:";

    private final CharacterStateService characterStateService;
    private final UserMapper userMapper;
    private final UserInterestProfile userInterestProfile;
    private final StringRedisTemplate redis;

    @Override
    public List<ProactiveTriggerEngine.AbsentUser> findAbsentUsers(Duration threshold) {
        Instant cutoff = Instant.now().minus(threshold);
        List<ProactiveTriggerEngine.AbsentUser> result = new ArrayList<>();
        for (Long userId : characterStateService.findUserIdsLastSeenBefore(cutoff)) {
            User user = userMapper.findById(userId);
            Instant lastSeen = characterStateService.load(userId).relationship().lastSeen();
            result.add(new ProactiveTriggerEngine.AbsentUser(
                    userId,
                    user == null ? "朋友" : nullToBlank(user.getNickname()),
                    lastSeen));
        }
        return result;
    }

    @Override
    public List<ProactiveTriggerEngine.UnreadPostDigest> findUnreadPostDigests(int minNewPosts) {
        // 标签未读摘要仍由轮询侧结合 PostService 统计；此处返回空，避免重复占位逻辑。
        return List.of();
    }

    /**
     * 最近 {@link #ACTIVE_WINDOW} 内活跃的用户。
     */
    public List<Long> findActiveUserIds() {
        Instant since = Instant.now().minus(ACTIVE_WINDOW);
        return characterStateService.findUserIdsActiveSince(since);
    }

    /**
     * 离开超过 7 天、适合发送回归简报的用户。
     */
    public List<ProactiveTriggerEngine.AbsentUser> findReturnBriefingCandidates() {
        Instant cutoff = Instant.now().minus(RETURN_BRIEFING_THRESHOLD);
        List<ProactiveTriggerEngine.AbsentUser> result = new ArrayList<>();
        for (Long userId : characterStateService.findUserIdsLastSeenBefore(cutoff)) {
            User user = userMapper.findById(userId);
            Instant lastSeen = characterStateService.load(userId).relationship().lastSeen();
            result.add(new ProactiveTriggerEngine.AbsentUser(
                    userId,
                    user == null ? "朋友" : nullToBlank(user.getNickname()),
                    lastSeen));
        }
        return result;
    }

    /**
     * 兴趣标签与文章标签有重叠的用户（排除创作者本人）。
     */
    public List<Long> findUsersInterestedInTags(Collection<String> tags, long excludeUserId) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        Set<String> normalizedTags = new HashSet<>();
        for (String tag : tags) {
            if (tag != null && !tag.isBlank()) {
                normalizedTags.add(tag.trim());
            }
        }
        if (normalizedTags.isEmpty()) {
            return List.of();
        }

        List<Long> matched = new ArrayList<>();
        for (Long userId : listInterestProfileUserIds()) {
            if (userId == null || userId == excludeUserId) {
                continue;
            }
            InterestProfile profile = userInterestProfile.buildProfile(userId);
            if (matchesTags(profile.tagWeights(), normalizedTags)) {
                matched.add(userId);
            }
        }
        return matched;
    }

    private static boolean matchesTags(Map<String, Double> tagWeights, Set<String> postTags) {
        if (tagWeights == null || tagWeights.isEmpty()) {
            return false;
        }
        for (String tag : postTags) {
            Double weight = tagWeights.get(tag);
            if (weight != null && weight >= TAG_MATCH_MIN_WEIGHT) {
                return true;
            }
        }
        return false;
    }

    private List<Long> listInterestProfileUserIds() {
        List<Long> ids = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(INTEREST_KEY_PREFIX + "*")
                .count(50)
                .build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String suffix = key.substring(INTEREST_KEY_PREFIX.length());
                try {
                    ids.add(Long.parseLong(suffix));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        if (ids.isEmpty()) {
            return characterStateService.listKnownUserIds();
        }
        return ids;
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
