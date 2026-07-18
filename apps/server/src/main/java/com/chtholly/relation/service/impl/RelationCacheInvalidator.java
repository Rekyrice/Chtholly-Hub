package com.chtholly.relation.service.impl;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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

    /** Creates an invalidator for the shared relation-list Redis cache. */
    public RelationCacheInvalidator(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
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
            keys.add(FOLLOWING_PREFIX + userId);
            keys.add(FOLLOWER_PREFIX + userId);
        }
        redis.delete(keys);
    }
}
