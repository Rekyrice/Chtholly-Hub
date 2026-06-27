package com.chtholly.post.feed;

import com.chtholly.post.mapper.PostMapper;
import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.service.RelationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 关注时间线 Redis 读写：推拉结合架构的推模式写入与大 V 标记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedTimelineService {

    static final String TIMELINE_KEY_PREFIX = "feed:timeline:";
    static final String BIGV_AUTHORS_KEY = "feed:bigv:authors";

    private static final int FOLLOWER_PAGE_SIZE = 500;

    private final StringRedisTemplate redis;
    private final FeedTimelineProperties properties;
    private final RelationMapper relationMapper;
    private final RelationService relationService;
    private final PostMapper postMapper;

    /**
     * 发帖后写入粉丝 timeline（推模式）或标记为大 V（拉模式）。
     */
    public void onPostPublished(long authorId, long postId, Instant publishTime) {
        int threshold = properties.getBigv().getThreshold();
        int followerCount = relationMapper.countFollowerActive(authorId);

        if (followerCount >= threshold) {
            redis.opsForSet().add(BIGV_AUTHORS_KEY, String.valueOf(authorId));
            log.debug("feed.timeline push skipped bigv authorId={} followers={}", authorId, followerCount);
            return;
        }

        double score = publishTime.toEpochMilli();
        String postIdStr = String.valueOf(postId);
        int offset = 0;
        int pushed = 0;

        while (true) {
            List<Long> followerIds = relationService.followers(authorId, FOLLOWER_PAGE_SIZE, offset);
            if (followerIds.isEmpty()) {
                break;
            }
            for (Long followerId : followerIds) {
                String key = timelineKey(followerId);
                redis.opsForZSet().add(key, postIdStr, score);
                trimOldEntries(followerId);
            }
            pushed += followerIds.size();
            if (followerIds.size() < FOLLOWER_PAGE_SIZE) {
                break;
            }
            offset += FOLLOWER_PAGE_SIZE;
        }

        log.info("feed.timeline pushed postId={} authorId={} followers={}", postId, authorId, pushed);
    }

    /**
     * 取消关注后，从读者 timeline 移除被取关作者的历史文章。
     */
    public void removeAuthorFromTimeline(long followerUserId, long authorId) {
        Instant since = Instant.now().minus(properties.getTimeline().getRetentionDays(), ChronoUnit.DAYS);
        List<Long> postIds = postMapper.listPublishedIdsByCreatorSince(authorId, since, 10_000);
        if (postIds.isEmpty()) {
            return;
        }

        String key = timelineKey(followerUserId);
        Object[] members = postIds.stream().map(String::valueOf).toArray();
        Long removed = redis.opsForZSet().remove(key, members);
        log.info("feed.timeline unfollow cleanup follower={} author={} removed={}", followerUserId, authorId, removed);
    }

    /**
     * 从 timeline ZSet 倒序读取 postId 列表。
     */
    public List<Long> getTimelinePostIds(long userId, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        String key = timelineKey(userId);
        Set<String> members = redis.opsForZSet().reverseRange(key, 0, limit - 1L);
        if (members == null || members.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = new ArrayList<>(members.size());
        for (String m : members) {
            try {
                ids.add(Long.parseLong(m));
            } catch (NumberFormatException ignored) {
                // 跳过脏数据
            }
        }
        return ids;
    }

    /**
     * 获取当前用户所关注且属于大 V 的作者 ID 列表。
     */
    public List<Long> getFollowedBigVAuthors(long userId) {
        Set<String> bigvMembers = redis.opsForSet().members(BIGV_AUTHORS_KEY);
        if (bigvMembers == null || bigvMembers.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> bigvIds = bigvMembers.stream()
                .map(s -> {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(id -> id != null)
                .collect(Collectors.toCollection(HashSet::new));

        if (bigvIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> following = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<Long> batch = relationService.following(userId, FOLLOWER_PAGE_SIZE, offset);
            if (batch.isEmpty()) {
                break;
            }
            for (Long id : batch) {
                if (bigvIds.contains(id)) {
                    following.add(id);
                }
            }
            if (batch.size() < FOLLOWER_PAGE_SIZE) {
                break;
            }
            offset += FOLLOWER_PAGE_SIZE;
        }
        return following;
    }

    /** 清理 timeline 中超过保留期的条目。 */
    public void trimOldEntries(long userId) {
        int days = properties.getTimeline().getRetentionDays();
        double maxScore = Instant.now().minus(days, ChronoUnit.DAYS).toEpochMilli();
        redis.opsForZSet().removeRangeByScore(timelineKey(userId), 0, maxScore);
    }

    static String timelineKey(long userId) {
        return TIMELINE_KEY_PREFIX + userId;
    }
}
