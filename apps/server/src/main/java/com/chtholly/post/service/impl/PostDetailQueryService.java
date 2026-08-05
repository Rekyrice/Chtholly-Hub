package com.chtholly.post.service.impl;

import com.chtholly.cache.hotkey.HotKeyDetector;
import com.chtholly.cache.config.CacheProperties;
import com.chtholly.cache.observability.CacheMetrics;
import com.chtholly.cache.singleflight.SingleFlightLockRegistry;
import com.chtholly.common.exception.ResourceNotFoundException;
import com.chtholly.common.web.HttpCacheHelper;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostDetailAudienceRow;
import com.chtholly.post.model.PostDetailEtagRow;
import com.chtholly.post.model.PostDetailRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Read model for post details, including access checks and multi-level caching.
 *
 * <p>Keeps the latency-sensitive detail query path independent from post mutation workflows.
 */
@Service
public class PostDetailQueryService {

    static final int LAYOUT_VERSION = 4;
    private static final Logger log = LoggerFactory.getLogger(PostDetailQueryService.class);

    private final PostMapper mapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final Cache<String, PostDetailResponse> localCache;
    private final HotKeyDetector hotKey;
    private final PostDetailViewerService viewerService;
    private final CacheProperties.ReadMode readMode;
    private final CacheMetrics cacheMetrics;
    private final SingleFlightLockRegistry singleFlight = new SingleFlightLockRegistry();

    public PostDetailQueryService(
            PostMapper mapper,
            ObjectMapper objectMapper,
            StringRedisTemplate redis,
            @Qualifier("postDetailCache") Cache<String, PostDetailResponse> localCache,
            HotKeyDetector hotKey,
            PostDetailViewerService viewerService,
            CacheProperties cacheProperties,
            CacheMetrics cacheMetrics
    ) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.localCache = localCache;
        this.hotKey = hotKey;
        this.viewerService = viewerService;
        this.readMode = cacheProperties.getReadMode();
        this.cacheMetrics = cacheMetrics;
    }

    static String cacheKey(long id) {
        return "post:detail:" + id + ":v" + LAYOUT_VERSION;
    }

    /** Loads post detail by ID with access control and live user counters. */
    @Transactional(readOnly = true)
    public PostDetailResponse getDetail(long id, Long currentUserId) {
        String pageKey = cacheKey(id);
        if (!readMode.usesCache()) {
            return loadFromDatabase(id, currentUserId, pageKey, false);
        }

        PostDetailResponse local = localCache.getIfPresent(pageKey);
        if (local != null) {
            if (authorizeCachedPayload(id, pageKey, local, currentUserId)) {
                recordHotKeyAndExtendTtl(id, pageKey);
                return viewerService.enrich(local, currentUserId, true);
            }
        }

        PostDetailResponse cached = processCacheHit(redis.opsForValue().get(pageKey), id, pageKey, currentUserId);
        if (cached != null) return cached;

        Supplier<PostDetailResponse> loader = () -> {
            PostDetailResponse afterFlight = processCacheHit(
                    redis.opsForValue().get(pageKey), id, pageKey, currentUserId);
            if (afterFlight != null) return afterFlight;
            return loadFromDatabase(id, currentUserId, pageKey, true);
        };
        return readMode.usesSingleFlight()
                ? singleFlight.runExclusive(pageKey, loader)
                : loader.get();
    }

    private PostDetailResponse loadFromDatabase(
            long id,
            Long currentUserId,
            String pageKey,
            boolean populateCache
    ) {
        cacheMetrics.recordSameKeyLoad();
        cacheMetrics.recordMysqlQuery();
        PostDetailRow row = mapper.findDetailById(id);
        if (row == null || "deleted".equals(row.getStatus())) {
            if (populateCache) {
                redis.opsForValue().set(pageKey, "NULL",
                        Duration.ofSeconds(30 + ThreadLocalRandom.current().nextInt(31)));
            }
            throw new ResourceNotFoundException("内容不存在");
        }
        viewerService.assertReadable(row, currentUserId);

        PostDetailResponse response = viewerService.mapRow(row);
        if (populateCache && isSharedCacheable(row)) {
            cache(pageKey, response);
        }
        return viewerService.enrich(response, currentUserId, false);
    }

    /** Resolves a post slug before using the same detail query path. */
    @Transactional(readOnly = true)
    public PostDetailResponse getDetailBySlug(String slug, Long currentUserId) {
        cacheMetrics.recordMysqlQuery();
        Long id = mapper.findIdBySlug(slug);
        if (id == null) throw new ResourceNotFoundException("内容不存在");
        return getDetail(id, currentUserId);
    }

    @Transactional(readOnly = true)
    public String computeEtag(long id) {
        cacheMetrics.recordMysqlQuery();
        return computeEtag(mapper.findDetailEtagById(id));
    }

    @Transactional(readOnly = true)
    public String computeEtagBySlug(String slug) {
        cacheMetrics.recordMysqlQuery();
        return computeEtag(mapper.findDetailEtagBySlug(slug));
    }

    private boolean isSharedCacheable(PostDetailRow row) {
        return "published".equals(row.getStatus()) && "public".equals(row.getVisible());
    }

    private boolean isSharedCacheable(PostDetailResponse response) {
        return "public".equals(response.visible());
    }

    private PostDetailResponse processCacheHit(String cached, long id, String pageKey, Long userId) {
        if (cached == null) return null;
        if ("NULL".equals(cached)) throw new ResourceNotFoundException("内容不存在");
        PostDetailResponse base;
        try {
            base = objectMapper.readValue(cached, PostDetailResponse.class);
        } catch (Exception e) {
            log.warn("Post detail cache deserialize failed, key={}", pageKey, e);
            return null;
        }
        if (!authorizeCachedPayload(id, pageKey, base, userId)) {
            return null;
        }
        localCache.put(pageKey, base);
        recordHotKeyAndExtendTtl(id, pageKey);
        return viewerService.enrich(base, userId, true);
    }

    private boolean authorizeCachedPayload(
            long id,
            String pageKey,
            PostDetailResponse cached,
            Long currentUserId) {
        cacheMetrics.recordMysqlQuery();
        PostDetailAudienceRow audience = mapper.findDetailAudienceById(id);
        if (audience == null || "deleted".equals(audience.getStatus())) {
            evictUnsafeSharedCache(pageKey);
            throw new ResourceNotFoundException("内容不存在");
        }
        boolean reusable = isSharedCacheable(audience) && isSharedCacheable(cached);
        if (!reusable) {
            evictUnsafeSharedCache(pageKey);
        }
        viewerService.assertReadable(audience, currentUserId);
        return reusable;
    }

    private boolean isSharedCacheable(PostDetailAudienceRow row) {
        return "published".equals(row.getStatus()) && "public".equals(row.getVisible());
    }

    private void evictUnsafeSharedCache(String pageKey) {
        localCache.invalidate(pageKey);
        try {
            redis.delete(pageKey);
        } catch (RuntimeException failure) {
            log.warn("Failed to evict non-public shared detail cache, key={}", pageKey, failure);
        }
    }

    private void cache(String pageKey, PostDetailResponse response) {
        try {
            int baseTtl = 60;
            int target = hotKey.ttlForPublic(baseTtl, pageKey);
            redis.opsForValue().set(pageKey, objectMapper.writeValueAsString(response),
                    Duration.ofSeconds(Math.max(target, baseTtl + ThreadLocalRandom.current().nextInt(30))));
            localCache.put(pageKey, response);
        } catch (Exception e) {
            log.warn("Failed to cache post detail, key={}", pageKey, e);
        }
    }

    private String computeEtag(PostDetailEtagRow row) {
        if (row == null || "deleted".equals(row.getStatus())) {
            throw new ResourceNotFoundException("内容不存在");
        }
        Instant updatedAt = row.getUpdateTime();
        Instant authorUpdatedAt = row.getAuthorUpdateTime();
        return HttpCacheHelper.hashEtag(row.getStatus(), String.valueOf(LAYOUT_VERSION),
                updatedAt != null ? updatedAt.toString() : "",
                authorUpdatedAt != null ? authorUpdatedAt.toString() : "");
    }

    private void recordHotKeyAndExtendTtl(long id, String pageKey) {
        String hotKeyId = "post:" + id;
        hotKey.record(hotKeyId);
        int target = hotKey.ttlForPublic(60, hotKeyId);
        extendTtl(pageKey, target);
        extendTtl("feed:item:" + id, target);
    }

    private void extendTtl(String key, int targetSeconds) {
        Long ttl = redis.getExpire(key);
        if (ttl != null && ttl < targetSeconds) redis.expire(key, Duration.ofSeconds(targetSeconds));
    }

}
