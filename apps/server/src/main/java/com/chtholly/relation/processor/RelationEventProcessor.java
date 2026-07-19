package com.chtholly.relation.processor;

import com.chtholly.relation.event.RelationEvent;
import com.chtholly.relation.mapper.RelationMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.chtholly.counter.service.UserCounterService;
import com.chtholly.relation.service.impl.RelationCacheInvalidator;
import com.chtholly.post.feed.FeedTimelineService;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Map;

/**
 * 关系事件处理器。
 * 职责：将 follower、关系 ZSet 与用户计数收敛到 following 表的当前权威状态。
 */
@Service
public class RelationEventProcessor {
    private final RelationMapper mapper;
    private final StringRedisTemplate redis;
    private final UserCounterService userCounterService;
    private final RelationCacheInvalidator cacheInvalidator;
    private final FeedTimelineService feedTimelineService;

    public RelationEventProcessor(RelationMapper mapper,
                                  StringRedisTemplate redis,
                                  UserCounterService userCounterService,
                                  RelationCacheInvalidator cacheInvalidator,
                                  FeedTimelineService feedTimelineService) {
        this.mapper = mapper;
        this.redis = redis;
        this.userCounterService = userCounterService;
        this.cacheInvalidator = cacheInvalidator;
        this.feedTimelineService = feedTimelineService;
    }

    /**
     * 事件只用于定位关系对；投影状态始终重新读取 following，避免乱序事件覆盖新终态。
     * @param evt 关系事件
     */
    public void process(RelationEvent evt) {
        reconcile(evt.fromUserId(), evt.toUserId());
    }

    /** Rebuilds one relation pair from the current following authority. */
    public void reconcile(long fromUserId, long toUserId) {
        Map<String, Object> activeFollowing = mapper.findActiveFollowingRow(fromUserId, toUserId);
        boolean active = activeFollowing != null;
        if (active) {
            long activeFollowingId = ((Number) activeFollowing.get("id")).longValue();
            Date createdAt = toDate(activeFollowing.get("createdAt"));
            mapper.insertFollower(activeFollowingId, toUserId, fromUserId, 1, createdAt);
            cacheInvalidator.invalidateLocalProjection(fromUserId, toUserId);
            updateActiveCache("uf:flws:" + fromUserId, String.valueOf(toUserId), createdAt.getTime());
            updateActiveCache("uf:fans:" + toUserId, String.valueOf(fromUserId), createdAt.getTime());
        } else {
            mapper.cancelFollower(toUserId, fromUserId);
            cacheInvalidator.invalidateLocalProjection(fromUserId, toUserId);
            removeFromCache("uf:flws:" + fromUserId, String.valueOf(toUserId));
            removeFromCache("uf:fans:" + toUserId, String.valueOf(fromUserId));
        }

        userCounterService.rebuildAllCounters(fromUserId);
        if (fromUserId != toUserId) {
            userCounterService.rebuildAllCounters(toUserId);
        }
        if (!active) {
            feedTimelineService.removeAuthorFromTimeline(fromUserId, toUserId);
        }
    }

    private void updateActiveCache(String key, String member, long score) {
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            redis.opsForZSet().add(key, member, score);
            redis.expire(key, Duration.ofHours(2));
        }
    }

    private void removeFromCache(String key, String member) {
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            redis.opsForZSet().remove(key, member);
            redis.expire(key, Duration.ofHours(2));
        }
    }

    private Date toDate(Object value) {
        if (value instanceof Date date) {
            return date;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return Timestamp.valueOf(localDateTime);
        }
        throw new IllegalStateException("Unsupported following createdAt value: "
                + (value == null ? "null" : value.getClass().getName()));
    }
}
