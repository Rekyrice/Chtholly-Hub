package com.chtholly.post.service.impl;

import com.chtholly.post.service.PostFeedService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.common.api.pagination.Pagination;
import com.chtholly.post.feed.FeedTimelineProperties;
import com.chtholly.post.feed.FeedTimelineService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostFeedRow;
import com.chtholly.counter.service.CounterService;
import com.chtholly.comment.service.CommentService;
import com.chtholly.post.util.FeedCursor;
import com.chtholly.user.model.PublicAuthorSnapshot;
import com.chtholly.user.service.PublicAuthorQueryService;
import com.github.benmanes.caffeine.cache.Cache;
import com.chtholly.cache.singleflight.SingleFlightLockRegistry;
import com.chtholly.cache.hotkey.HotKeyDetector;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;

/**
 * Multi-level cache feed service for public and personal post listings.
 *
 * <p>Architecture: Caffeine L1 (per-config TTL) → Redis L2 fragment cache (ids/item/hasMore) → MySQL.
 * Uses {@link SingleFlightLockRegistry} for stampede prevention and {@link HotKeyDetector}
 * for dynamic TTL extension on hot posts/pages.
 *
 * @see HotKeyDetector
 * @see PostServiceImpl
 */
@Service
public class PostFeedServiceImpl implements PostFeedService {

    private final PostMapper mapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CounterService counterService;
    private final CommentService commentService;
    private final Cache<String, PageResponse<FeedItemResponse>> feedPublicCache;
    private final HotKeyDetector hotKey;
    private final PersonalPostFeedService personalFeedService;
    private final PublicAuthorQueryService publicAuthorQueryService;
    private static final Logger log = LoggerFactory.getLogger(PostFeedServiceImpl.class);
    private static final String FEED_PUBLIC_PAGES_KEY = "feed:public:pages";
    private static final int LAYOUT_VER = 3;
    private final SingleFlightLockRegistry singleFlight = new SingleFlightLockRegistry();

    /**
     * 构造函数：注入 Mapper、Redis、对象映射器、计数服务与本地缓存。
     * @param mapper 数据访问层
     * @param redis Redis 客户端
     * @param objectMapper JSON 序列化/反序列化器
     * @param counterService 点赞/收藏计数服务
     * @param feedPublicCache 首页公共 Feed 本地缓存
     * @param feedMineCache 我的发布 Feed 本地缓存
     * @param hotKey 热点 Key 检测器，用于动态延长 TTL
     */
    @Autowired
    public PostFeedServiceImpl(
            PostMapper mapper,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            CounterService counterService,
            CommentService commentService,
            @Qualifier("feedPublicCache") Cache<String, PageResponse<FeedItemResponse>> feedPublicCache,
            HotKeyDetector hotKey,
            PersonalPostFeedService personalFeedService,
            PublicAuthorQueryService publicAuthorQueryService
    ) {
        this.mapper = mapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.counterService = counterService;
        this.commentService = commentService;
        this.feedPublicCache = feedPublicCache;
        this.hotKey = hotKey;
        this.personalFeedService = personalFeedService;
        this.publicAuthorQueryService = publicAuthorQueryService;
    }

    /**
     * 生成公共 Feed 页面的缓存 Key（offset 分页）。
     */
    private String cacheKeyByPage(int page, int size) {
        return "feed:public:" + size + ":" + page + ":v" + LAYOUT_VER;
    }

    /**
     * 生成公共 Feed 页面的缓存 Key（游标分页）。
     */
    private String cacheKeyByCursor(String cursorSlot, int size) {
        return "feed:public:" + size + ":" + cursorSlot + ":v" + LAYOUT_VER;
    }

    /**
     * Fetches a page of public posts (newest first).
     *
     * <p>{@code cursor} 优先于 {@code page}：传 cursor 时走游标分页，否则走 offset 分页（向后兼容）。
     */
    @Override
    public PageResponse<FeedItemResponse> getPublicFeed(Integer page, String cursor, int size, Long ownerId, String tag,
                                          Long currentUserIdNullable) {
        if (tag != null && !tag.isBlank()) {
            int safePage = page != null ? Math.max(page, 1) : 1;
            return getPublicFeedByTag(tag.trim(), ownerId, safePage, size, currentUserIdNullable);
        }
        if (ownerId != null) {
            int safePage = page != null ? Math.max(page, 1) : 1;
            return getPublicFeedByOwner(ownerId, safePage, size, currentUserIdNullable);
        }
        if (cursor != null) {
            return getPublicFeedByCursor(cursor, size, currentUserIdNullable);
        }
        int safePage = page != null ? Math.max(page, 1) : 1;
        return getPublicFeedByOffset(safePage, size, currentUserIdNullable);
    }

    @Override
    public String publicFeedPageKey(Integer page, String cursor, int size, Long ownerId, String tag) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        if (tag != null && !tag.isBlank()) {
            int safePage = page != null ? Math.max(page, 1) : 1;
            String ownerPart = ownerId != null ? String.valueOf(ownerId) : "all";
            return "feed:tag:" + tag.trim() + ":" + ownerPart + ":" + safeSize + ":" + safePage + ":v" + LAYOUT_VER;
        }
        if (ownerId != null) {
            int safePage = page != null ? Math.max(page, 1) : 1;
            return "feed:owner:" + ownerId + ":" + safeSize + ":" + safePage + ":v" + LAYOUT_VER;
        }
        if (cursor != null) {
            return cacheKeyByCursor(FeedCursor.cacheSlot(cursor), safeSize);
        }
        int safePage = page != null ? Math.max(page, 1) : 1;
        return cacheKeyByPage(safePage, safeSize);
    }

    /**
     * 公开 Feed — offset 分页（向后兼容 page 参数）。
     */
    private PageResponse<FeedItemResponse> getPublicFeedByOffset(int safePage, int size, Long currentUserIdNullable) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        String localPageKey = cacheKeyByPage(safePage, safeSize);

        long hourSlot = System.currentTimeMillis() / 3600000L;
        String idsKey = "feed:public:ids:" + safeSize + ":" + hourSlot + ":" + safePage;
        String hasMoreKey = idsKey + ":hasMore";

        PageResponse<FeedItemResponse> local = feedPublicCache.getIfPresent(localPageKey);
        if (local != null && local.items() != null) {
            for (FeedItemResponse item : local.items()) {
                recordItemHotKey(item.id());
            }
            log.info("feed.public source=local localPageKey={} page={} size={}", localPageKey, safePage, safeSize);
            List<FeedItemResponse> enrichedLocal = enrich(local.items(), currentUserIdNullable);
            return PageResponse.offset(enrichedLocal, safePage, safeSize, 0L, local.hasMore(), local.nextCursor());
        }

        PageResponse<FeedItemResponse> fromCache = assembleFromCache(idsKey, hasMoreKey, safePage, safeSize, currentUserIdNullable);
        if (fromCache != null) {
            feedPublicCache.put(localPageKey, fromCache);
            if (fromCache.items() != null) {
                for (FeedItemResponse item : fromCache.items()) {
                    recordItemHotKey(item.id());
                }
            }
            log.info("feed.public source=3tier localPageKey={} page={} size={}", localPageKey, safePage, safeSize);
            return fromCache;
        }

        return singleFlight.runExclusive(idsKey, () -> {
            PageResponse<FeedItemResponse> again = assembleFromCache(idsKey, hasMoreKey, safePage, safeSize, currentUserIdNullable);
            if (again != null) {
                feedPublicCache.put(localPageKey, again);
                if (again.items() != null) {
                    for (FeedItemResponse item : again.items()) {
                        recordItemHotKey(item.id());
                    }
                }
                log.info("feed.public source=3tier(after-flight) localPageKey={} page={} size={}", localPageKey, safePage, safeSize);
                return again;
            }

            int offset = (safePage - 1) * safeSize;
            List<PostFeedRow> rows = mapper.listFeedPublic(safeSize + 1, offset);
            boolean hasMore = rows.size() > safeSize;
            if (hasMore) {
                rows = rows.subList(0, safeSize);
            }

            List<FeedItemResponse> items = mapRowsToItems(rows, null, false);
            String nextCursor = hasMore ? nextCursorFromRows(rows) : null;

            PageResponse<FeedItemResponse> respForCache = PageResponse.offset(items, safePage, safeSize, 0L, hasMore, nextCursor);
            int baseTtl = 60;
            int jitter = ThreadLocalRandom.current().nextInt(30);
            Duration frTtl = Duration.ofSeconds(baseTtl + jitter);

            writeCaches(localPageKey, idsKey, hasMoreKey, safeSize, rows, items, hasMore, nextCursor, frTtl);
            feedPublicCache.put(localPageKey, respForCache);

            List<FeedItemResponse> enriched = enrich(items, currentUserIdNullable);
            log.info("feed.public source=db localPageKey={} page={} size={} hasMore={}", localPageKey, safePage, safeSize, hasMore);
            return buildResponse(enriched, safePage, safeSize, hasMore, nextCursor);
        });
    }

    /**
     * 公开 Feed — 游标分页；cursor 为空表示首页（缓存槽位 head）。
     */
    private PageResponse<FeedItemResponse> getPublicFeedByCursor(String cursor, int size, Long currentUserIdNullable) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        String cursorSlot = FeedCursor.cacheSlot(cursor);
        String localPageKey = cacheKeyByCursor(cursorSlot, safeSize);

        long hourSlot = System.currentTimeMillis() / 3600000L;
        String idsKey = "feed:public:ids:" + safeSize + ":" + hourSlot + ":" + cursorSlot;
        String hasMoreKey = idsKey + ":hasMore";

        PageResponse<FeedItemResponse> local = feedPublicCache.getIfPresent(localPageKey);
        if (local != null && local.items() != null) {
            for (FeedItemResponse item : local.items()) {
                recordItemHotKey(item.id());
            }
            log.info("feed.public source=local cursor={} size={}", cursorSlot, safeSize);
            List<FeedItemResponse> enrichedLocal = enrich(local.items(), currentUserIdNullable);
            return buildResponse(enrichedLocal, 0, safeSize, local.hasMore(), local.nextCursor());
        }

        PageResponse<FeedItemResponse> fromCache = assembleFromCache(idsKey, hasMoreKey, 0, safeSize, currentUserIdNullable);
        if (fromCache != null) {
            feedPublicCache.put(localPageKey, stripUserFlags(fromCache));
            if (fromCache.items() != null) {
                for (FeedItemResponse item : fromCache.items()) {
                    recordItemHotKey(item.id());
                }
            }
            log.info("feed.public source=3tier cursor={} size={}", cursorSlot, safeSize);
            return fromCache;
        }

        return singleFlight.runExclusive(idsKey, () -> {
            PageResponse<FeedItemResponse> again = assembleFromCache(idsKey, hasMoreKey, 0, safeSize, currentUserIdNullable);
            if (again != null) {
                feedPublicCache.put(localPageKey, stripUserFlags(again));
                if (again.items() != null) {
                    for (FeedItemResponse item : again.items()) {
                        recordItemHotKey(item.id());
                    }
                }
                return again;
            }

            List<PostFeedRow> rows;
            if (cursor == null || cursor.isBlank()) {
                rows = mapper.listFeedPublic(safeSize + 1, 0);
            } else {
                FeedCursor.FeedCursorPoint point = FeedCursor.require(cursor);
                rows = mapper.listFeedPublicByCursor(point.publishTime(), point.postId(), safeSize + 1);
            }

            boolean hasMore = rows.size() > safeSize;
            if (hasMore) {
                rows = rows.subList(0, safeSize);
            }

            List<FeedItemResponse> items = mapRowsToItems(rows, null, false);
            String nextCursor = hasMore ? nextCursorFromRows(rows) : null;

            PageResponse<FeedItemResponse> respForCache = PageResponse.offset(items, 0, safeSize, 0L, hasMore, nextCursor);
            int baseTtl = 60;
            int jitter = ThreadLocalRandom.current().nextInt(30);
            Duration frTtl = Duration.ofSeconds(baseTtl + jitter);

            writeCaches(localPageKey, idsKey, hasMoreKey, safeSize, rows, items, hasMore, nextCursor, frTtl);
            feedPublicCache.put(localPageKey, respForCache);

            List<FeedItemResponse> enriched = enrich(items, currentUserIdNullable);
            log.info("feed.public source=db cursor={} size={} hasMore={}", cursorSlot, safeSize, hasMore);
            return buildResponse(enriched, 0, safeSize, hasMore, nextCursor);
        });
    }

    private PageResponse<FeedItemResponse> buildResponse(List<FeedItemResponse> items, int page, int size,
                                           boolean hasMore, String nextCursor) {
        return PageResponse.offset(items, page, size, 0L, hasMore, nextCursor);
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

    /** 剥离用户态标志，供 L1 缓存共享片段。 */
    private PageResponse<FeedItemResponse> stripUserFlags(PageResponse<FeedItemResponse> page) {
        if (page.items() == null) {
            return page;
        }
        List<FeedItemResponse> neutral = new ArrayList<>(page.items().size());
        for (FeedItemResponse it : page.items()) {
            neutral.add(it.withoutUserFlags());
        }
        return PageResponse.offset(neutral, page.page(), page.size(), page.total(), page.hasMore(), page.nextCursor());
    }

    /**
     * 按创作者过滤的公开 Feed（不走公共缓存，Phase A 站长列表专用）。
     */
    private PageResponse<FeedItemResponse> getPublicFeedByOwner(long ownerId, int page, int size, Long currentUserIdNullable) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * safeSize;

        List<PostFeedRow> rows = mapper.listFeedPublicByCreator(ownerId, safeSize + 1, offset);
        boolean hasMore = rows.size() > safeSize;
        if (hasMore) {
            rows = rows.subList(0, safeSize);
        }

        // 个人主页公开列表需要 isTop，访客才能看到置顶排序与标记
        List<FeedItemResponse> items = enrich(mapRowsToItems(rows, null, true), currentUserIdNullable);
        String nextCursor = hasMore ? nextCursorFromRows(rows) : null;
        log.info("feed.public source=db ownerId={} page={} size={} hasMore={}", ownerId, safePage, safeSize, hasMore);
        return PageResponse.offset(items, safePage, safeSize, 0L, hasMore, nextCursor);
    }

    /** 按标签过滤的公开 Feed（不走页面级缓存，M1 直查 DB）。 */
    private PageResponse<FeedItemResponse> getPublicFeedByTag(String tagName, Long ownerId, int page, int size, Long currentUserIdNullable) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * safeSize;

        List<PostFeedRow> rows = mapper.listFeedPublicByTag(tagName, ownerId, safeSize + 1, offset);
        boolean hasMore = rows.size() > safeSize;
        if (hasMore) {
            rows = rows.subList(0, safeSize);
        }

        List<FeedItemResponse> items = enrich(mapRowsToItems(rows, null, false), currentUserIdNullable);
        String nextCursor = hasMore ? nextCursorFromRows(rows) : null;
        log.info("feed.public source=db tag={} ownerId={} page={} size={} hasMore={}", tagName, ownerId, safePage, safeSize, hasMore);
        return PageResponse.offset(items, safePage, safeSize, 0L, hasMore, nextCursor);
    }

    /**
     * 记录单个内容条目的热度，并尝试延长其相关片段缓存的 TTL。
     * @param itemId 内容 ID
     */
    private void recordItemHotKey(String itemId) {
        // 使用内容 ID 作为热点统计 Key，而不是页面 Key
        String hotKeyId = "post:" + itemId;
        hotKey.record(hotKeyId);
        
        int baseTtl = 60;
        int target = hotKey.ttlForPublic(baseTtl, hotKeyId);
        
        // 延长该内容的详情片段缓存
        String itemKey = "feed:item:" + itemId;
        Long itemTtl = redis.getExpire(itemKey);
        if (itemTtl < target) {
            redis.expire(itemKey, Duration.ofSeconds(target));
        }
    }

    /**
     * 叠加用户维度 liked/faved；使用 Redis Pipeline 批量查询，避免 N 次往返。
     */
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

        Map<Long, PublicAuthorSnapshot> authors = Map.of();
        List<Long> authorIds = base.stream()
                .map(FeedItemResponse::authorId)
                .map(PostFeedServiceImpl::parseLongOrNull)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
        if (!authorIds.isEmpty()) {
            try {
                authors = publicAuthorQueryService.findByIds(authorIds);
            } catch (RuntimeException e) {
                log.warn("Failed to refresh feed author profiles, authorIds={}", authorIds, e);
            }
        }

        List<FeedItemResponse> out = new ArrayList<>(base.size());
        for (FeedItemResponse it : base) {
            long postId = Long.parseLong(it.id());
            boolean liked = uid != null && Boolean.TRUE.equals(likedBatch.get(postId));
            boolean faved = uid != null && Boolean.TRUE.equals(favBatch.get(postId));
            FeedItemResponse enriched = it.withUserFlags(liked, faved)
                    .withCommentCount(commentCounts.getOrDefault(postId, 0L));
            Long authorId = parseLongOrNull(it.authorId());
            PublicAuthorSnapshot author = authorId == null ? null : authors.get(authorId);
            if (author != null) {
                enriched = enriched.withAuthor(
                        String.valueOf(author.id()), author.handle(), author.avatar(), author.nickname(), author.tagsJson());
            } else if (enriched.authorNickname() == null || enriched.authorNickname().isBlank()) {
                enriched = enriched.withAuthor(enriched.authorId(), enriched.authorHandle(), enriched.authorAvatar(),
                        "已注销用户", enriched.tagJson());
            }
            out.add(enriched);
        }
        return out;
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 从 Redis 片段缓存组装页面：
     * - idsKey：列表 ID 顺序
     * - itemKey：每个条目基础信息
     * - countKey：点赞/收藏计数
     * 若缺片段则回源修补并写回软缓存。
     * @param idsKey Redis 列表 Key
     * @param hasMoreKey Redis 软缓存 hasMore Key
     * @param page 页码
     * @param size 每页大小
     * @param uid 当前用户 ID（用于 liked/faved）
     * @return 组装完成的页面；不存在时返回 null
     */
    private PageResponse<FeedItemResponse> assembleFromCache(String idsKey, String hasMoreKey, int page, int size, Long uid) {
        // 需要展示帖子的 ID 列表
        List<String> idList = redis.opsForList().range(idsKey, 0, size - 1);
        String hasMoreStr = redis.opsForValue().get(hasMoreKey);
        if (idList == null || idList.isEmpty()) {
            return null;
        }

        // 构造内容元数据（标题，内容等）的 Redis Key
        List<String> itemKeys = new ArrayList<>(idList.size());
        for (String id : idList) {
            itemKeys.add("feed:item:" + id);
        }
        // 批量获取帖子 元数据
        List<String> itemJsons = redis.opsForValue().multiGet(itemKeys);

        List<FeedItemResponse> items = new ArrayList<>(idList.size());

        for (int i = 0; i < idList.size(); i++) {
            String itemJson = (itemJsons != null && i < itemJsons.size()) ? itemJsons.get(i) : null;
            if (itemJson == null) {
                // 缺失元数据片段，触发回源
                return null;
            }

            try {
                FeedItemResponse item = objectMapper.readValue(itemJson, FeedItemResponse.class);
                if (item.authorId() == null || item.authorId().isBlank()) {
                    return null;
                }
                items.add(item);
            } catch (Exception e) {
                return null;
            }
        }

        Map<String, Map<String, Long>> countsBatch =
                counterService.getCountsBatch("post", idList, List.of("like", "fav"));

        List<FeedItemResponse> withCounts = new ArrayList<>(items.size());
        for (FeedItemResponse base : items) {
            Map<String, Long> counts = countsBatch.getOrDefault(base.id(), Map.of());
            withCounts.add(base.withCounts(
                    counts.getOrDefault("like", 0L),
                    counts.getOrDefault("fav", 0L)).withoutUserFlags());
        }

        List<FeedItemResponse> enriched = enrich(withCounts, uid);

        boolean hasMore = hasMoreStr != null ? "1".equals(hasMoreStr) : (idList.size() == size);
        String nextCursor = redis.opsForValue().get(idsKey + ":nextCursor");

        return PageResponse.offset(enriched, page, size, 0L, hasMore, nextCursor);
    }

    /**
     * 写入片段缓存与软缓存：
     * - idsKey：ID 列表（中 TTL）
     * - item：条目片段（中 TTL）
     * - hasMore：软缓存，满页时缓存 true 10~20s，否则 10s
     * 注意：不再写入 Redis 整页缓存 (pageKey)，避免双重存储。
     * @param pageKey 页面缓存 Key (用于反向索引引用)
     * @param idsKey ID 列表 Key
     * @param hasMoreKey 软缓存 Key
     * @param size 每页大小
     * @param rows 原始行数据
     * @param items 条目列表（计数已填充，liked/faved 为空）
     * @param hasMore 是否还有更多
     * @param nextCursor 下一页游标（可空）
     * @param frTtl 片段缓存 TTL
     */
    private void writeCaches(String pageKey, String idsKey, String hasMoreKey, int size, List<PostFeedRow> rows,
                             List<FeedItemResponse> items, boolean hasMore, String nextCursor, Duration frTtl) {
        List<String> idVals = new ArrayList<>();

        for (PostFeedRow r : rows) {
            idVals.add(String.valueOf(r.getId()));
        }

        if (!idVals.isEmpty()) {
            redis.opsForList().leftPushAll(idsKey, idVals);
            redis.expire(idsKey, frTtl);
            if (idVals.size() == size && hasMore) {
                redis.opsForValue().set(hasMoreKey, "1", Duration.ofSeconds(10 + ThreadLocalRandom.current().nextInt(11)));
            } else {
                redis.opsForValue().set(hasMoreKey, hasMore ? "1" : "0", Duration.ofSeconds(10));
            }
            if (nextCursor != null) {
                redis.opsForValue().set(idsKey + ":nextCursor", nextCursor, frTtl);
            }
        }

        // 页面键索引（Sorted Set，score = 写入时间戳），供批量失效与容量清理
        indexFeedPublicPage(pageKey);

        for (FeedItemResponse it : items) {
            // 反向索引：按小时为每个内容建立“页面引用关系”，支持内容更新时快速定位受影响页面
            long hourSlot = System.currentTimeMillis() / 3600000L;
            String idxKey = "feed:public:index:" + it.id() + ":" + hourSlot;
            redis.opsForSet().add(idxKey, pageKey);
            redis.expire(idxKey, frTtl);

            try {
                String itemKey = "feed:item:" + it.id();
                String itemJson = objectMapper.writeValueAsString(it);
                redis.opsForValue().set(itemKey, itemJson, frTtl);
            } catch (Exception e) {
                log.warn("Failed to cache feed item, id={}: {}", it.id(), e.getMessage());
            }
        }
    }

    /**
     * 写入 feed 页面索引。旧版使用 SET，新版使用 ZSET；若类型不一致则自动迁移。
     */
    private void indexFeedPublicPage(String pageKey) {
        DataType type = redis.type(FEED_PUBLIC_PAGES_KEY);
        if (type == DataType.SET) {
            Set<String> members = redis.opsForSet().members(FEED_PUBLIC_PAGES_KEY);
            redis.delete(FEED_PUBLIC_PAGES_KEY);
            if (members != null) {
                long now = System.currentTimeMillis();
                for (String member : members) {
                    redis.opsForZSet().add(FEED_PUBLIC_PAGES_KEY, member, now);
                }
            }
            log.info("feed.public migrated {} from SET to ZSET", FEED_PUBLIC_PAGES_KEY);
        } else if (type != DataType.NONE && type != DataType.ZSET) {
            redis.delete(FEED_PUBLIC_PAGES_KEY);
            log.warn("feed.public reset unexpected key type {} for {}", type, FEED_PUBLIC_PAGES_KEY);
        }
        redis.opsForZSet().add(FEED_PUBLIC_PAGES_KEY, pageKey, System.currentTimeMillis());
    }

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
        return items;
    }


    @Override
    public void invalidateMyPublishedCache(long userId) {
        personalFeedService.invalidateMyPublishedCache(userId);
    }

    @Override
    public PageResponse<FeedItemResponse> getMyPublished(long userId, int page, int size) {
        return personalFeedService.getMyPublished(userId, page, size);
    }

    @Override
    public PageResponse<FeedItemResponse> getFollowingFeed(long userId, int page, int size) {
        return personalFeedService.getFollowingFeed(userId, page, size);
    }

}
