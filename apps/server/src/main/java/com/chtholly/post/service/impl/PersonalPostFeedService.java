package com.chtholly.post.service.impl;

import com.chtholly.cache.hotkey.HotKeyDetector;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.feed.FeedTimelineProperties;
import com.chtholly.post.feed.FeedTimelineService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostFeedRow;
import com.chtholly.post.util.FeedCursor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Owns user-scoped published feeds and the hybrid following timeline. */
@Service
public class PersonalPostFeedService {
    private static final Logger log = LoggerFactory.getLogger(PersonalPostFeedService.class);

    private final PostMapper mapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Cache<String, PageResponse<FeedItemResponse>> feedMineCache;
    private final HotKeyDetector hotKey;
    private final FeedTimelineService feedTimelineService;
    private final FeedTimelineProperties feedTimelineProperties;
    private final FeedItemAssembler assembler;

    /**
     * Creates the user-scoped feed service.
     *
     * @param mapper post feed persistence mapper
     * @param redis Redis client for personal pages and timeline fragments
     * @param objectMapper cached payload codec
     * @param feedMineCache process-local personal-feed cache
     * @param hotKey hot-key detector for adaptive TTLs
     * @param feedTimelineService following timeline reader
     * @param feedTimelineProperties following timeline configuration
     * @param assembler shared feed-item assembler
     */
    public PersonalPostFeedService(
            PostMapper mapper,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Qualifier("feedMineCache") Cache<String, PageResponse<FeedItemResponse>> feedMineCache,
            HotKeyDetector hotKey,
            FeedTimelineService feedTimelineService,
            FeedTimelineProperties feedTimelineProperties,
            FeedItemAssembler assembler
    ) {
        this.mapper = mapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.feedMineCache = feedMineCache;
        this.hotKey = hotKey;
        this.feedTimelineService = feedTimelineService;
        this.feedTimelineProperties = feedTimelineProperties;
        this.assembler = assembler;
    }

    private String nextCursorFromRows(List<PostFeedRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        PostFeedRow last = rows.get(rows.size() - 1);
        if (last.getPublishTime() == null || last.getId() == null) {
            return null;
        }
        return FeedCursor.encode(last.getPublishTime(), last.getId());
    }


    /**
     * 生成“我的发布”列表的缓存 Key（用户维度）。
     * @param userId 用户 ID
     * @param page 页码
     * @param size 每页大小
     * @return Redis 页面缓存 Key
     */
    private String myCacheKey(long userId, int page, int size) {
        return "feed:mine:" + userId + ":" + size + ":" + page;
    }

    /**
     * Drops Caffeine + Redis pages for {@code feed:mine:{userId}:*} after pin/visibility/delete.
     *
     * @param userId owner of the personal feed
     */
    public void invalidateMyPublishedCache(long userId) {
        String prefix = "feed:mine:" + userId + ":";
        try {
            feedMineCache.asMap().keySet().removeIf(key -> key != null && key.startsWith(prefix));
        } catch (Exception e) {
            log.warn("feed.mine L1 invalidate failed, userId={}", userId, e);
        }

        String pattern = prefix + "*";
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            log.warn("feed.mine Redis SCAN failed, pattern={}", pattern, e);
            return;
        }
        if (!keys.isEmpty()) {
            try {
                redis.delete(keys);
            } catch (Exception e) {
                log.warn("feed.mine Redis delete failed, userId={} size={}", userId, keys.size(), e);
            }
        }
        log.info("feed.mine invalidated userId={} redisKeys={}", userId, keys.size());
    }

    /**
     * Fetches the authenticated user's published posts (includes {@code isTop} flag).
     *
     * @param userId Current user ID.
     * @param page   Page number (1-indexed).
     * @param size   Items per page (clamped to 1–50).
     * @return Personal feed page; shorter Redis TTL than public feed.
     */
    public PageResponse<FeedItemResponse> getMyPublished(long userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);
        String key = myCacheKey(userId, safePage, safeSize);

        PageResponse<FeedItemResponse> local = feedMineCache.getIfPresent(key);
        if (local != null) {
            hotKey.record(key);
            maybeExtendTtlMine(key);
            log.info("feed.mine source=local key={} page={} size={} user={}", key, safePage, safeSize, userId);
            List<FeedItemResponse> enriched = assembler.enrich(ensureMineAuthorId(local.items(), userId), userId);
            return PageResponse.offset(enriched, local.page(), local.size(), local.total(),
                    local.hasMore(), local.nextCursor());
        }

        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            try {
                PageResponse<FeedItemResponse> cachedResp = objectMapper.readValue(cached,
                        new TypeReference<PageResponse<FeedItemResponse>>() {});
                boolean hasCounts = cachedResp.items() != null && cachedResp.items().stream()
                        .allMatch(it -> it.likeCount() != null && it.favoriteCount() != null);
                if (hasCounts) {
                    // 覆盖 liked/faved，确保老缓存也能返回用户维度状态
                    feedMineCache.put(key, cachedResp);
                    hotKey.record(key);
                    maybeExtendTtlMine(key);
                    log.info("feed.mine source=page key={} page={} size={} user={}", key, safePage, safeSize, userId);
                List<FeedItemResponse> enriched = assembler.enrich(
                        ensureMineAuthorId(cachedResp.items(), userId), userId);
                return PageResponse.offset(enriched, cachedResp.page(), cachedResp.size(), cachedResp.total(),
                        cachedResp.hasMore(), cachedResp.nextCursor());
            }
            } catch (Exception e) {
                log.warn("Feed mine cache deserialize failed, key={}: {}", key, e.getMessage());
            }
        }

        int offset = (safePage - 1) * safeSize;
        List<PostFeedRow> rows = mapper.listMyPublished(userId, safeSize + 1, offset);
        boolean hasMore = rows.size() > safeSize;
        if (hasMore) rows = rows.subList(0, safeSize);

        List<FeedItemResponse> items = assembler.fromRows(rows, userId, true);

        long total = mapper.countMyPublished(userId);
        PageResponse<FeedItemResponse> resp = PageResponse.offset(items, safePage, safeSize, total, hasMore,
                hasMore ? nextCursorFromRows(rows) : null);
        try {
            String json = objectMapper.writeValueAsString(resp);
            // 个人列表 baseTtl=30s（比公开 Feed 更短）：用户更频繁改稿/置顶，接受更高回源率换一致性
            int baseTtl = 30;
            int jitter = ThreadLocalRandom.current().nextInt(20);
            redis.opsForValue().set(key, json, Duration.ofSeconds(baseTtl + jitter));
            feedMineCache.put(key, resp);
            hotKey.record(key);
        } catch (Exception e) {
            log.warn("Failed to cache feed mine page, key={}: {}", key, e.getMessage());
        }
        log.info("feed.mine source=db key={} page={} size={} user={} hasMore={}", key, safePage, safeSize, userId, hasMore);
        return resp;
    }

    /**
     * 关注时间线：合并 Redis 推模式 timeline 与大 V 拉模式近期文章，按发布时间降序分页。
     */
    public PageResponse<FeedItemResponse> getFollowingFeed(long userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);
        int candidateLimit = safePage * safeSize + safeSize + 1;

        List<Long> timelineIds = feedTimelineService.getTimelinePostIds(userId, candidateLimit);
        List<PostFeedRow> bigVRows = loadBigVRecentPosts(feedTimelineService.getFollowedBigVAuthors(userId));

        List<PostFeedRow> timelineRows = timelineIds.isEmpty()
                ? Collections.emptyList()
                : mapper.listFeedRowsByIds(timelineIds);

        Map<Long, PostFeedRow> merged = new LinkedHashMap<>();
        for (PostFeedRow row : timelineRows) {
            merged.putIfAbsent(row.getId(), row);
        }
        for (PostFeedRow row : bigVRows) {
            merged.putIfAbsent(row.getId(), row);
        }

        List<PostFeedRow> sorted = new ArrayList<>(merged.values());
        sorted.sort(Comparator.comparing(
                PostFeedRow::getPublishTime,
                Comparator.nullsLast(Comparator.reverseOrder())));

        int offset = (safePage - 1) * safeSize;
        List<PostFeedRow> slice = sorted.stream()
                .skip(offset)
                .limit(safeSize + 1L)
                .toList();
        boolean hasMore = slice.size() > safeSize;
        List<PostFeedRow> pageRows = hasMore ? slice.subList(0, safeSize) : slice;

        List<FeedItemResponse> items = assembler.fromRowsBatch(pageRows, userId);
        log.info("feed.following user={} page={} size={} timeline={} bigv={} merged={} hasMore={}",
                userId, safePage, safeSize, timelineRows.size(), bigVRows.size(), sorted.size(), hasMore);
        return PageResponse.offset(items, safePage, safeSize, 0L, hasMore,
                hasMore ? nextCursorFromRows(pageRows) : null);
    }

    /**
     * 拉模式：读取所关注大 V 的近期文章，按作者维度缓存 5 分钟。
     */
    private List<PostFeedRow> loadBigVRecentPosts(List<Long> authorIds) {
        if (authorIds.isEmpty()) {
            return Collections.emptyList();
        }
        int pullHours = feedTimelineProperties.getTimeline().getBigvPullHours();
        int cacheSeconds = feedTimelineProperties.getTimeline().getBigvCacheSeconds();
        Instant since = Instant.now().minus(pullHours, ChronoUnit.HOURS);

        List<PostFeedRow> all = new ArrayList<>();
        for (Long authorId : authorIds) {
            String cacheKey = "feed:bigv:posts:" + authorId;
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                try {
                    List<PostFeedRow> rows = objectMapper.readValue(cached, new TypeReference<>() {});
                    if (rows.stream().allMatch(row -> row.getAuthorId() != null)) {
                        all.addAll(rows);
                        continue;
                    }
                    log.debug("feed.following bigv cache layout miss authorId={}", authorId);
                } catch (Exception e) {
                    log.debug("feed.following bigv cache parse miss authorId={}", authorId);
                }
            }

            List<PostFeedRow> rows = mapper.listRecentPublicByCreators(List.of(authorId), since, 50);
            try {
                redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(rows),
                        Duration.ofSeconds(cacheSeconds));
            } catch (Exception e) {
                log.warn("feed.following bigv cache write failed authorId={}: {}", authorId, e.getMessage());
            }
            all.addAll(rows);
        }
        return all;
    }

    private List<FeedItemResponse> ensureMineAuthorId(List<FeedItemResponse> items, long userId) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        String authorId = String.valueOf(userId);
        return items.stream()
                .map(item -> item.authorId() == null || item.authorId().isBlank()
                        ? item.withAuthor(authorId, item.authorHandle(), item.authorAvatar(),
                                item.authorNickname(), item.tagJson())
                        : item)
                .toList();
    }

    /**
     * 根据热点级别动态延长“我的发布”页面缓存 TTL。
     * @param key 页面缓存 Key
     */
    private void maybeExtendTtlMine(String key) {
        int baseTtl = 30;
        int target = hotKey.ttlForMine(baseTtl, key);
        Long currentTtl = redis.getExpire(key);
        if (currentTtl == null || currentTtl < target) {
            redis.expire(key, Duration.ofSeconds(target));
        }
    }
}
