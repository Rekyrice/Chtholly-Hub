package com.chtholly.post.service.impl;

import com.chtholly.cache.hotkey.HotKeyDetector;
import com.chtholly.cache.config.CacheProperties;
import com.chtholly.cache.observability.CacheMetrics;
import com.chtholly.cache.singleflight.SingleFlightLockRegistry;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.common.exception.ResourceNotFoundException;
import com.chtholly.common.web.HttpCacheHelper;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostDetailEtagRow;
import com.chtholly.post.model.PostDetailRow;
import com.chtholly.user.model.PublicAuthorSnapshot;
import com.chtholly.user.service.PublicAuthorQueryService;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private final CounterService counterService;
    private final StringRedisTemplate redis;
    private final Cache<String, PostDetailResponse> localCache;
    private final HotKeyDetector hotKey;
    private final PublicAuthorQueryService publicAuthorQueryService;
    private final CacheProperties.ReadMode readMode;
    private final CacheMetrics cacheMetrics;
    private final SingleFlightLockRegistry singleFlight = new SingleFlightLockRegistry();

    public PostDetailQueryService(
            PostMapper mapper,
            ObjectMapper objectMapper,
            CounterService counterService,
            StringRedisTemplate redis,
            @Qualifier("postDetailCache") Cache<String, PostDetailResponse> localCache,
            HotKeyDetector hotKey,
            PublicAuthorQueryService publicAuthorQueryService,
            CacheProperties cacheProperties,
            CacheMetrics cacheMetrics
    ) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.counterService = counterService;
        this.redis = redis;
        this.localCache = localCache;
        this.hotKey = hotKey;
        this.publicAuthorQueryService = publicAuthorQueryService;
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
            assertCachedReadable(local, currentUserId);
            recordHotKeyAndExtendTtl(id, pageKey);
            return enrich(local, currentUserId, true);
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
        assertReadable(row, currentUserId);

        Map<String, Long> counts = counterService.getCounts(
                "post", String.valueOf(row.getId()), List.of("like", "fav"));
        PostDetailResponse response = mapRow(row, counts);
        if (populateCache && isSharedCacheable(row)) {
            cache(pageKey, response);
        }
        return enrich(response, currentUserId, false);
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

    private void assertReadable(PostDetailRow row, Long currentUserId) {
        boolean isPublic = isSharedCacheable(row);
        boolean isOwner = currentUserId != null && currentUserId.equals(row.getCreatorId());
        if (!isPublic && !isOwner) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无权限查看");
        }
    }

    private boolean isSharedCacheable(PostDetailRow row) {
        return "published".equals(row.getStatus()) && "public".equals(row.getVisible());
    }

    private void assertCachedReadable(PostDetailResponse response, Long currentUserId) {
        boolean isPublic = "public".equals(response.visible());
        boolean isOwner = currentUserId != null && String.valueOf(currentUserId).equals(response.authorId());
        if (!isPublic && !isOwner) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无权限查看");
        }
    }

    private PostDetailResponse mapRow(PostDetailRow row, Map<String, Long> counts) {
        return new PostDetailResponse(
                String.valueOf(row.getId()), row.getSlug(), row.getTitle(), row.getDescription(),
                row.getContentUrl(), parseArray(row.getImgUrls()), parseArray(row.getTags()),
                String.valueOf(row.getCreatorId()), row.getAuthorHandle(), row.getAuthorAvatar(),
                row.getAuthorNickname(), row.getAuthorBio(), row.getAuthorTagJson(),
                counts.getOrDefault("like", 0L), counts.getOrDefault("fav", 0L),
                null, null, row.getIsTop(), row.getVisible(), row.getType(), row.getPublishTime());
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
        assertCachedReadable(base, userId);
        localCache.put(pageKey, base);
        recordHotKeyAndExtendTtl(id, pageKey);
        return enrich(base, userId, true);
    }

    private PostDetailResponse enrich(PostDetailResponse base, Long userId, boolean refreshCounts) {
        Long likes = base.likeCount();
        Long favorites = base.favoriteCount();
        if (refreshCounts) {
            Map<String, Long> counts = counterService.getCounts("post", base.id(), List.of("like", "fav"));
            likes = counts.getOrDefault("like", likes == null ? 0L : likes);
            favorites = counts.getOrDefault("fav", favorites == null ? 0L : favorites);
        }

        String authorHandle = base.authorHandle();
        String authorAvatar = base.authorAvatar();
        String authorNickname = base.authorNickname();
        String authorBio = base.authorBio();
        String authorTagJson = base.authorTagJson();
        try {
            long authorId = Long.parseLong(base.authorId());
            PublicAuthorSnapshot snapshot = publicAuthorQueryService.findById(authorId).orElse(null);
            if (snapshot != null) {
                authorHandle = snapshot.handle();
                authorAvatar = snapshot.avatar();
                authorNickname = snapshot.nickname();
                authorBio = snapshot.bio();
                authorTagJson = snapshot.tagsJson();
            }
        } catch (RuntimeException e) {
            log.warn("Failed to refresh public author profile, authorId={}", base.authorId(), e);
        }
        if (authorNickname == null || authorNickname.isBlank()) {
            authorNickname = "已注销用户";
        }
        return new PostDetailResponse(
                base.id(), base.slug(), base.title(), base.description(), base.contentUrl(), base.images(), base.tags(),
                base.authorId(), authorHandle, authorAvatar, authorNickname, authorBio, authorTagJson, likes, favorites,
                userId != null && counterService.isLiked("post", base.id(), userId),
                userId != null && counterService.isFaved("post", base.id(), userId),
                base.isTop(), base.visible(), base.type(), base.publishTime());
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

    private List<String> parseArray(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("Invalid post detail JSON array", e);
            return Collections.emptyList();
        }
    }
}
