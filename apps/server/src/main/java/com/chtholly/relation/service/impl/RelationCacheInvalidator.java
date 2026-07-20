package com.chtholly.relation.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Invalidates Redis-backed following and follower lists after direct relation-table maintenance. */
@Component
public final class RelationCacheInvalidator {

    private static final String FOLLOWING_PREFIX = "uf:flws:";
    private static final String FOLLOWER_PREFIX = "uf:fans:";

    private final StringRedisTemplate redis;
    private final Cache<Long, List<Long>> followingTopCache;
    private final Cache<Long, List<Long>> followerTopCache;

    /** Creates an invalidator for the shared relation-list Redis cache. */
    public RelationCacheInvalidator(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.followingTopCache = newTopCache();
        this.followerTopCache = newTopCache();
    }

    List<Long> getFollowingTop(long userId) {
        return followingTopCache.getIfPresent(userId);
    }

    List<Long> getFollowerTop(long userId) {
        return followerTopCache.getIfPresent(userId);
    }

    void putFollowingTop(long userId, List<Long> ids) {
        followingTopCache.put(userId, List.copyOf(ids));
    }

    void putFollowerTop(long userId, List<Long> ids) {
        followerTopCache.put(userId, List.copyOf(ids));
    }

    /** Invalidates only the two local list projections affected by one relation pair. */
    public void invalidateLocalProjection(long fromUserId, long toUserId) {
        followingTopCache.invalidate(fromUserId);
        followerTopCache.invalidate(toUserId);
    }

    /**
     * Deletes both relation-list caches for every affected user in one Redis batch.
     *
     * @param userIds relation endpoints whose database rows changed
     * @throws NullPointerException when the endpoint collection is null
     * @throws IllegalArgumentException when an endpoint ID is null or non-positive
     * @throws RuntimeException when Redis rejects the batch deletion
     */
    public void invalidateUsers(Collection<Long> userIds) {
        Objects.requireNonNull(userIds, "userIds");
        LinkedHashSet<Long> uniqueUserIds = new LinkedHashSet<>();
        for (Long userId : userIds) {
            if (userId == null || userId <= 0) {
                throw new IllegalArgumentException("relation cache user IDs must be positive");
            }
            uniqueUserIds.add(userId);
        }
        if (uniqueUserIds.isEmpty()) {
            return;
        }

        List<String> keys = new ArrayList<>(uniqueUserIds.size() * 2);
        for (long userId : uniqueUserIds) {
            followingTopCache.invalidate(userId);
            followerTopCache.invalidate(userId);
            keys.add(FOLLOWING_PREFIX + userId);
            keys.add(FOLLOWER_PREFIX + userId);
        }
        redis.delete(keys);
    }

    private static Cache<Long, List<Long>> newTopCache() {
        return Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }
}
