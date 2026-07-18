package com.chtholly.post.service.impl;

import com.chtholly.cache.hotkey.HotKeyDetector;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.counter.service.CounterService;
import com.chtholly.comment.service.CommentService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.feed.FeedTimelineProperties;
import com.chtholly.post.feed.FeedTimelineService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostFeedRow;
import com.chtholly.post.util.FeedCursor;
import com.chtholly.user.model.PublicAuthorSnapshot;
import com.chtholly.user.service.PublicAuthorQueryService;
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
    private final CounterService counterService;
    private final CommentService commentService;
    private final Cache<String, PageResponse<FeedItemResponse>> feedMineCache;
    private final HotKeyDetector hotKey;
    private final FeedTimelineService feedTimelineService;
    private final FeedTimelineProperties feedTimelineProperties;
    private final PublicAuthorQueryService publicAuthorQueryService;

    public PersonalPostFeedService(
            PostMapper mapper,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            CounterService counterService,
            CommentService commentService,
            @Qualifier("feedMineCache") Cache<String, PageResponse<FeedItemResponse>> feedMineCache,
            HotKeyDetector hotKey,
            FeedTimelineService feedTimelineService,
            FeedTimelineProperties feedTimelineProperties,
            PublicAuthorQueryService publicAuthorQueryService
    ) {
        this.mapper = mapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.counterService = counterService;
        this.commentService = commentService;
        this.feedMineCache = feedMineCache;
        this.hotKey = hotKey;
        this.feedTimelineService = feedTimelineService;
        this.feedTimelineProperties = feedTimelineProperties;
        this.publicAuthorQueryService = publicAuthorQueryService;
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


    private List<FeedItemResponse> enrich(List<FeedItemResponse> base, Long uid) {
        if (base == null || base.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> postIds = new ArrayList<>(base.size());
        for (FeedItemResponse it : base) {
            postIds.add(Long.parseLong(it.id()));
        }
        Map<Long, Long> commentCounts = commentService.countActiveByPostIds(postIds);

        Map<Long, Boolean> likedBatch = Map.of();
        Map<Long, Boolean> favBatch = Map.of();
        if (uid != null) {
            likedBatch = counterService.batchIsLiked(uid, postIds);
            favBatch = counterService.batchIsFaved(uid, postIds);
        }

        List<FeedItemResponse> out = new ArrayList<>(base.size());
        for (FeedItemResponse it : base) {
            long postId = Long.parseLong(it.id());
            boolean liked = uid != null && Boolean.TRUE.equals(likedBatch.get(postId));
            boolean faved = uid != null && Boolean.TRUE.equals(favBatch.get(postId));
            out.add(it.withUserFlags(liked, faved)
                    .withCommentCount(commentCounts.getOrDefault(postId, 0L)));
        }
        return refreshAuthors(out);
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
            List<FeedItemResponse> enriched = enrich(ensureMineAuthorId(local.items(), userId), userId);
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
                List<FeedItemResponse> enriched = enrich(ensureMineAuthorId(cachedResp.items(), userId), userId);
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

        List<FeedItemResponse> items = mapRowsToItems(rows, userId, true);

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

        List<FeedItemResponse> items = mapRowsToItemsBatch(pageRows, userId);
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

    /**
     * 解析 JSON 数组字符串为 List<String>。
     * @param json JSON 数组字符串
     * @return 字符串列表；解析失败或空字符串返回空列表
     */
    /**
     * 将数据库行映射为响应条目。
     * 计数通过计数服务填充；liked/faved 按需计算；isTop 仅在个人列表返回。
     * @param rows 查询结果行
     * @param userIdNullable 当前用户 ID（可空）
     * @param includeIsTop 是否在响应中包含 isTop
     * @return 条目列表
     */
    private List<FeedItemResponse> mapRowsToItems(List<PostFeedRow> rows, Long userIdNullable, boolean includeIsTop) {
        List<FeedItemResponse> items = new ArrayList<>(rows.size());

        for (PostFeedRow r : rows) {
            Map<String, Long> counts = counterService.getCounts("post", String.valueOf(r.getId()), List.of("like", "fav"));
            Boolean liked = userIdNullable != null && counterService.isLiked("post", String.valueOf(r.getId()), userIdNullable);
            Boolean faved = userIdNullable != null && counterService.isFaved("post", String.valueOf(r.getId()), userIdNullable);
            Boolean isTop = includeIsTop ? r.getIsTop() : null;

            items.add(FeedItemResponse.fromRow(
                    r,
                    FeedItemResponse.CounterSnapshot.from(counts),
                    liked,
                    faved).withTop(isTop));
        }
        return withCommentCounts(refreshAuthors(items));
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

    private List<FeedItemResponse> refreshAuthors(List<FeedItemResponse> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> authorIds = items.stream()
                .map(FeedItemResponse::authorId)
                .map(PersonalPostFeedService::parseLongOrNull)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new), List::copyOf));
        Map<Long, PublicAuthorSnapshot> authors = Map.of();
        if (!authorIds.isEmpty()) {
            try {
                authors = publicAuthorQueryService.findByIds(authorIds);
            } catch (RuntimeException exception) {
                log.warn("Failed to refresh personal feed author profiles, authorIds={}", authorIds, exception);
            }
        }
        Map<Long, PublicAuthorSnapshot> resolved = authors;
        return items.stream().map(item -> {
            Long authorId = parseLongOrNull(item.authorId());
            PublicAuthorSnapshot author = authorId == null ? null : resolved.get(authorId);
            if (author != null) {
                return item.withAuthor(String.valueOf(author.id()), author.handle(), author.avatar(),
                        author.nickname(), author.tagsJson());
            }
            if (item.authorNickname() == null || item.authorNickname().isBlank()) {
                return item.withAuthor(item.authorId(), item.authorHandle(), item.authorAvatar(),
                        "已注销用户", item.tagJson());
            }
            return item;
        }).toList();
    }

    private List<FeedItemResponse> withCommentCounts(List<FeedItemResponse> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> postIds = items.stream()
                .map(FeedItemResponse::id)
                .map(PersonalPostFeedService::parseLongOrNull)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, Long> counts = commentService.countActiveByPostIds(postIds);
        return items.stream()
                .map(item -> {
                    Long postId = parseLongOrNull(item.id());
                    return item.withCommentCount(postId == null ? 0L : counts.getOrDefault(postId, 0L));
                })
                .toList();
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 批量映射 Feed 行并填充计数与点赞/收藏状态（Pipeline 优化）。
     */
    private List<FeedItemResponse> mapRowsToItemsBatch(List<PostFeedRow> rows, long userId) {
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> idStr = rows.stream().map(r -> String.valueOf(r.getId())).toList();
        List<Long> idLong = rows.stream().map(PostFeedRow::getId).toList();

        Map<String, Map<String, Long>> countsBatch =
                counterService.getCountsBatch("post", idStr, List.of("like", "fav"));
        Map<Long, Boolean> likedBatch = counterService.batchIsLiked(userId, idLong);
        Map<Long, Boolean> favBatch = counterService.batchIsFaved(userId, idLong);

        List<FeedItemResponse> items = new ArrayList<>(rows.size());
        for (PostFeedRow r : rows) {
            Map<String, Long> counts = countsBatch.getOrDefault(String.valueOf(r.getId()), Map.of());
            boolean liked = Boolean.TRUE.equals(likedBatch.get(r.getId()));
            boolean faved = Boolean.TRUE.equals(favBatch.get(r.getId()));

            items.add(FeedItemResponse.fromRow(
                    r,
                    FeedItemResponse.CounterSnapshot.from(counts),
                    liked,
                    faved).withTop(null));
        }
        return withCommentCounts(refreshAuthors(items));
    }



    /**
     * 根据热点级别动态延长“我的发布”页面缓存 TTL。
     * @param key 页面缓存 Key
     */
    private void maybeExtendTtlMine (String key) {
        int baseTtl = 30;
        int target = hotKey.ttlForMine(baseTtl, key);
        Long currentTtl = redis.getExpire(key);
        if (currentTtl < target) {
            redis.expire(key, Duration.ofSeconds(target));
        }
    }
}
