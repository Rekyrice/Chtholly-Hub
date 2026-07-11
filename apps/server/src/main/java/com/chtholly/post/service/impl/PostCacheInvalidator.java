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
        String detailKey = PostDetailQueryService.cacheKey(postId);
        try {
            redis.delete(detailKey);
        } catch (Exception e) {
            log.warn("Redis detail cache invalidation failed, key={}", detailKey, e);
        }
        try {
            postDetailCache.invalidate(detailKey);
        } catch (Exception e) {
            log.warn("Local detail cache invalidation failed, key={}", detailKey, e);
        }
        invalidatePublicFeedPages(postId);
    }

    private void invalidatePublicFeedPages(long postId) {
        long hourSlot = System.currentTimeMillis() / 3_600_000L;
        for (long slot : List.of(hourSlot, hourSlot - 1)) {
            String indexKey = "feed:public:index:" + postId + ":" + slot;
            try {
                Set<String> pageKeys = redis.opsForSet().members(indexKey);
                if (pageKeys == null || pageKeys.isEmpty()) continue;
                for (String pageKey : pageKeys) {
                    if (pageKey == null || pageKey.isBlank()) continue;
                    feedPublicCache.invalidate(pageKey);
                    redis.opsForSet().remove(indexKey, pageKey);
                }
            } catch (Exception e) {
                log.warn("Public Feed cache invalidation failed, indexKey={}", indexKey, e);
            }
        }
    }
}
