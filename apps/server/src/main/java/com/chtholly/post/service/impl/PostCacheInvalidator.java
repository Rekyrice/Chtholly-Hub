package com.chtholly.post.service.impl;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Invalidates post detail and reverse-indexed public Feed cache entries after mutations. */
@Component
@Slf4j
public class PostCacheInvalidator {

    private static final String PUBLIC_PAGES_KEY = "feed:public:pages";

    private final StringRedisTemplate redis;
    private final Cache<String, PageResponse<FeedItemResponse>> feedPublicCache;
    private final Cache<String, PostDetailResponse> postDetailCache;

    public PostCacheInvalidator(
            StringRedisTemplate redis,
            @Qualifier("feedPublicCache") Cache<String, PageResponse<FeedItemResponse>> feedPublicCache,
            @Qualifier("postDetailCache") Cache<String, PostDetailResponse> postDetailCache
    ) {
        this.redis = redis;
        this.feedPublicCache = feedPublicCache;
        this.postDetailCache = postDetailCache;
    }

    /** Clears detail caches and public Feed pages that contain the mutated post. */
    public void invalidate(long postId) {
        invalidate(postId, false);
    }

    /**
     * Clears post caches for durable replay and reports Redis failures after all cleanup attempts.
     *
     * @param postId mutated post identifier
     */
    public void invalidateStrict(long postId) {
        invalidate(postId, true);
    }

    private void invalidate(long postId, boolean strict) {
        RedisFailureCollector failures = new RedisFailureCollector();
        String detailKey = PostDetailQueryService.cacheKey(postId);
        try {
            redis.delete(detailKey);
        } catch (Exception e) {
            log.warn("Redis detail cache invalidation failed, key={}", detailKey, e);
            failures.record(e);
        }
        try {
            postDetailCache.invalidate(detailKey);
        } catch (Exception e) {
            log.warn("Local detail cache invalidation failed, key={}", detailKey, e);
        }
        try {
            redis.delete("feed:item:" + postId);
        } catch (Exception e) {
            log.warn("Redis Feed item invalidation failed, postId={}", postId, e);
            failures.record(e);
        }
        invalidatePublicFeedPages(postId, failures);
        if (strict) {
            failures.throwIfAny("Strict post cache invalidation failed, postId=" + postId);
        }
    }

    /** Clears every currently indexed public Feed page after publishing a new post. */
    public void invalidateAllPublicFeedPages() {
        invalidateAllPublicFeedPages(false);
    }

    /** Clears the entire public Feed for durable replay and reports Redis failures after all attempts. */
    public void invalidateAllPublicFeedPagesStrict() {
        invalidateAllPublicFeedPages(true);
    }

    private void invalidateAllPublicFeedPages(boolean strict) {
        long hourSlot = System.currentTimeMillis() / 3_600_000L;
        try {
            feedPublicCache.invalidateAll();
        } catch (Exception e) {
            log.warn("Local public Feed cache reset failed", e);
        }
        RedisFailureCollector failures = new RedisFailureCollector();
        Set<String> pageKeys = Set.of();
        try {
            Set<String> indexedKeys = redis.opsForZSet().range(PUBLIC_PAGES_KEY, 0, -1);
            pageKeys = indexedKeys == null ? Set.of() : indexedKeys;
        } catch (Exception e) {
            log.warn("Public Feed page index lookup failed", e);
            failures.record(e);
        }
        for (String pageKey : pageKeys) {
            if (pageKey == null || pageKey.isBlank()) continue;
            deletePageFragments(pageKey, hourSlot, failures);
            deletePageFragments(pageKey, hourSlot - 1, failures);
        }
        try {
            redis.delete(PUBLIC_PAGES_KEY);
        } catch (Exception e) {
            log.warn("Public Feed cache reset failed", e);
            failures.record(e);
        }
        if (strict) {
            failures.throwIfAny("Strict public Feed cache invalidation failed");
        }
    }

    private void invalidatePublicFeedPages(long postId, RedisFailureCollector failures) {
        long hourSlot = System.currentTimeMillis() / 3_600_000L;
        for (long slot : List.of(hourSlot, hourSlot - 1)) {
            String indexKey = "feed:public:index:" + postId + ":" + slot;
            try {
                Set<String> pageKeys = redis.opsForSet().members(indexKey);
                if (pageKeys == null || pageKeys.isEmpty()) continue;
                for (String pageKey : pageKeys) {
                    if (pageKey == null || pageKey.isBlank()) continue;
                    feedPublicCache.invalidate(pageKey);
                    deletePageFragments(pageKey, slot, failures);
                    try {
                        redis.opsForSet().remove(indexKey, pageKey);
                    } catch (Exception e) {
                        log.warn("Public Feed reverse-index cleanup failed, indexKey={}, pageKey={}",
                                indexKey, pageKey, e);
                        failures.record(e);
                    }
                }
            } catch (Exception e) {
                log.warn("Public Feed cache invalidation failed, indexKey={}", indexKey, e);
                feedPublicCache.invalidateAll();
                failures.record(e);
            }
        }
    }

    private void deletePageFragments(
            String pageKey,
            long hourSlot,
            RedisFailureCollector failures) {
        String[] parts = pageKey.split(":", 5);
        if (parts.length != 5
                || !"feed".equals(parts[0])
                || !"public".equals(parts[1])
                || !parts[4].startsWith("v")) {
            log.warn("Skip invalid public Feed page key during invalidation, key={}", pageKey);
            return;
        }
        String idsKey = "feed:public:ids:" + parts[2] + ":" + hourSlot + ":" + parts[3];
        try {
            redis.delete(List.of(idsKey, idsKey + ":hasMore", idsKey + ":nextCursor"));
        } catch (Exception e) {
            log.warn("Public Feed page fragment invalidation failed, pageKey={}, hourSlot={}",
                    pageKey, hourSlot, e);
            failures.record(e);
        }
    }

    private static final class RedisFailureCollector {

        private RuntimeException failure;

        void record(Exception cause) {
            if (failure == null) {
                failure = new IllegalStateException("Redis cache invalidation failed", cause);
                return;
            }
            failure.addSuppressed(cause);
        }

        void throwIfAny(String message) {
            if (failure != null) {
                throw new IllegalStateException(message, failure);
            }
        }
    }
}
