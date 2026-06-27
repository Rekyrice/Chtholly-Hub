package com.chtholly.post.service.impl;

import com.chtholly.post.service.PostFeedService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.api.dto.FeedPageResponse;
import com.chtholly.post.feed.FeedTimelineProperties;
import com.chtholly.post.feed.FeedTimelineService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostFeedRow;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.util.FeedCursor;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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
    private final Cache<String, FeedPageResponse> feedPublicCache;
    private final Cache<String, FeedPageResponse> feedMineCache;
    private final HotKeyDetector hotKey;
    private final FeedTimelineService feedTimelineService;
    private final FeedTimelineProperties feedTimelineProperties;
    private static final Logger log = LoggerFactory.getLogger(PostFeedServiceImpl.class);
    private static final int LAYOUT_VER = 2;
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
            @Qualifier("feedPublicCache") Cache<String, FeedPageResponse> feedPublicCache,
            @Qualifier("feedMineCache") Cache<String, FeedPageResponse> feedMineCache,
            HotKeyDetector hotKey,
            FeedTimelineService feedTimelineService,
            FeedTimelineProperties feedTimelineProperties
    ) {
        this.mapper = mapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.counterService = counterService;
        this.feedPublicCache = feedPublicCache;
        this.feedMineCache = feedMineCache;
        this.hotKey = hotKey;
        this.feedTimelineService = feedTimelineService;
        this.feedTimelineProperties = feedTimelineProperties;
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
    public FeedPageResponse getPublicFeed(Integer page, String cursor, int size, Long ownerId, String tag,
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

    /**
     * 公开 Feed — offset 分页（向后兼容 page 参数）。
     */
    private FeedPageResponse getPublicFeedByOffset(int safePage, int size, Long currentUserIdNullable) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        String localPageKey = cacheKeyByPage(safePage, safeSize);

        long hourSlot = System.currentTimeMillis() / 3600000L;
        String idsKey = "feed:public:ids:" + safeSize + ":" + hourSlot + ":" + safePage;
        String hasMoreKey = idsKey + ":hasMore";

        FeedPageResponse local = feedPublicCache.getIfPresent(localPageKey);
        if (local != null && local.items() != null) {
            for (FeedItemResponse item : local.items()) {
                recordItemHotKey(item.id());
            }
            log.info("feed.public source=local localPageKey={} page={} size={}", localPageKey, safePage, safeSize);
            List<FeedItemResponse> enrichedLocal = enrich(local.items(), currentUserIdNullable);
            return buildResponse(enrichedLocal, safePage, safeSize, local.hasMore(), local.nextCursor());
        }

        FeedPageResponse fromCache = assembleFromCache(idsKey, hasMoreKey, safePage, safeSize, currentUserIdNullable);
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
            FeedPageResponse again = assembleFromCache(idsKey, hasMoreKey, safePage, safeSize, currentUserIdNullable);
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

            FeedPageResponse respForCache = new FeedPageResponse(items, safePage, safeSize, hasMore, nextCursor);
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
    private FeedPageResponse getPublicFeedByCursor(String cursor, int size, Long currentUserIdNullable) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        String cursorSlot = FeedCursor.cacheSlot(cursor);
        String localPageKey = cacheKeyByCursor(cursorSlot, safeSize);

        long hourSlot = System.currentTimeMillis() / 3600000L;
        String idsKey = "feed:public:ids:" + safeSize + ":" + hourSlot + ":" + cursorSlot;
        String hasMoreKey = idsKey + ":hasMore";

        FeedPageResponse local = feedPublicCache.getIfPresent(localPageKey);
        if (local != null && local.items() != null) {
            for (FeedItemResponse item : local.items()) {
                recordItemHotKey(item.id());
            }
            log.info("feed.public source=local cursor={} size={}", cursorSlot, safeSize);
            List<FeedItemResponse> enrichedLocal = enrich(local.items(), currentUserIdNullable);
            return buildResponse(enrichedLocal, 0, safeSize, local.hasMore(), local.nextCursor());
        }

        FeedPageResponse fromCache = assembleFromCache(idsKey, hasMoreKey, 0, safeSize, currentUserIdNullable);
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
            FeedPageResponse again = assembleFromCache(idsKey, hasMoreKey, 0, safeSize, currentUserIdNullable);
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

            FeedPageResponse respForCache = new FeedPageResponse(items, 0, safeSize, hasMore, nextCursor);
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

    private FeedPageResponse buildResponse(List<FeedItemResponse> items, int page, int size,
                                           boolean hasMore, String nextCursor) {
        return new FeedPageResponse(items, page, size, hasMore, nextCursor);
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
    private FeedPageResponse stripUserFlags(FeedPageResponse page) {
        if (page.items() == null) {
            return page;
        }
        List<FeedItemResponse> neutral = new ArrayList<>(page.items().size());
        for (FeedItemResponse it : page.items()) {
            neutral.add(new FeedItemResponse(
                    it.id(), it.slug(), it.title(), it.description(), it.coverImage(),
                    it.tags(), it.authorAvatar(), it.authorNickname(), it.tagJson(),
                    it.likeCount(), it.favoriteCount(), null, null, it.isTop()));
        }
        return new FeedPageResponse(neutral, page.page(), page.size(), page.hasMore(), page.nextCursor());
    }

    /**
     * 按创作者过滤的公开 Feed（不走公共缓存，Phase A 站长列表专用）。
     */
    private FeedPageResponse getPublicFeedByOwner(long ownerId, int page, int size, Long currentUserIdNullable) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * safeSize;

        List<PostFeedRow> rows = mapper.listFeedPublicByCreator(ownerId, safeSize + 1, offset);
        boolean hasMore = rows.size() > safeSize;
        if (hasMore) {
            rows = rows.subList(0, safeSize);
        }

        List<FeedItemResponse> items = mapRowsToItems(rows, currentUserIdNullable, false);
        String nextCursor = hasMore ? nextCursorFromRows(rows) : null;
        log.info("feed.public source=db ownerId={} page={} size={} hasMore={}", ownerId, safePage, safeSize, hasMore);
        return new FeedPageResponse(items, safePage, safeSize, hasMore, nextCursor);
    }

    /** 按标签过滤的公开 Feed（不走页面级缓存，M1 直查 DB）。 */
    private FeedPageResponse getPublicFeedByTag(String tagName, Long ownerId, int page, int size, Long currentUserIdNullable) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * safeSize;

        List<PostFeedRow> rows = mapper.listFeedPublicByTag(tagName, ownerId, safeSize + 1, offset);
        boolean hasMore = rows.size() > safeSize;
        if (hasMore) {
            rows = rows.subList(0, safeSize);
        }

        List<FeedItemResponse> items = mapRowsToItems(rows, currentUserIdNullable, false);
        String nextCursor = hasMore ? nextCursorFromRows(rows) : null;
        log.info("feed.public source=db tag={} ownerId={} page={} size={} hasMore={}", tagName, ownerId, safePage, safeSize, hasMore);
        return new FeedPageResponse(items, safePage, safeSize, hasMore, nextCursor);
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

        Map<Long, Boolean> likedBatch = Map.of();
        Map<Long, Boolean> favBatch = Map.of();
        if (uid != null) {
            List<Long> postIds = new ArrayList<>(base.size());
            for (FeedItemResponse it : base) {
                postIds.add(Long.parseLong(it.id()));
            }
            likedBatch = counterService.batchIsLiked(uid, postIds);
            favBatch = counterService.batchIsFaved(uid, postIds);
        }

        List<FeedItemResponse> out = new ArrayList<>(base.size());
        for (FeedItemResponse it : base) {
            long postId = Long.parseLong(it.id());
            boolean liked = uid != null && Boolean.TRUE.equals(likedBatch.get(postId));
            boolean faved = uid != null && Boolean.TRUE.equals(favBatch.get(postId));
            out.add(new FeedItemResponse(
                    it.id(),
                    it.slug(),
                    it.title(),
                    it.description(),
                    it.coverImage(),
                    it.tags(),
                    it.authorAvatar(),
                    it.authorNickname(),
                    it.tagJson(),
                    it.likeCount(),
                    it.favoriteCount(),
                    liked,
                    faved,
                    it.isTop()
            ));
        }
        return out;
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
    private FeedPageResponse assembleFromCache(String idsKey, String hasMoreKey, int page, int size, Long uid) {
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
                items.add(objectMapper.readValue(itemJson, FeedItemResponse.class));
            } catch (Exception e) {
                return null;
            }
        }

        Map<String, Map<String, Long>> countsBatch =
                counterService.getCountsBatch("post", idList, List.of("like", "fav"));

        List<FeedItemResponse> withCounts = new ArrayList<>(items.size());
        for (FeedItemResponse base : items) {
            Map<String, Long> counts = countsBatch.getOrDefault(base.id(), Map.of());
            withCounts.add(new FeedItemResponse(
                    base.id(),
                    base.slug(),
                    base.title(),
                    base.description(),
                    base.coverImage(),
                    base.tags(),
                    base.authorAvatar(),
                    base.authorNickname(),
                    base.tagJson(),
                    counts.getOrDefault("like", 0L),
                    counts.getOrDefault("fav", 0L),
                    null,
                    null,
                    base.isTop()));
        }

        List<FeedItemResponse> enriched = enrich(withCounts, uid);

        boolean hasMore = hasMoreStr != null ? "1".equals(hasMoreStr) : (idList.size() == size);
        String nextCursor = redis.opsForValue().get(idsKey + ":nextCursor");

        return new FeedPageResponse(enriched, page, size, hasMore, nextCursor);
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
        redis.opsForZSet().add("feed:public:pages", pageKey, System.currentTimeMillis());

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
     * Fetches the authenticated user's published posts (includes {@code isTop} flag).
     *
     * @param userId Current user ID.
     * @param page   Page number (1-indexed).
     * @param size   Items per page (clamped to 1–50).
     * @return Personal feed page; shorter Redis TTL than public feed.
     */
    public FeedPageResponse getMyPublished(long userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);
        String key = myCacheKey(userId, safePage, safeSize);

        FeedPageResponse local = feedMineCache.getIfPresent(key);
        if (local != null) {
            hotKey.record(key);
            maybeExtendTtlMine(key);
            log.info("feed.mine source=local key={} page={} size={} user={}", key, safePage, safeSize, userId);
            return local;
        }

        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            try {
                FeedPageResponse cachedResp = objectMapper.readValue(cached, FeedPageResponse.class);
                boolean hasCounts = cachedResp.items() != null && cachedResp.items().stream()
                        .allMatch(it -> it.likeCount() != null && it.favoriteCount() != null);
                if (hasCounts) {
                    // 覆盖 liked/faved，确保老缓存也能返回用户维度状态
                    feedMineCache.put(key, cachedResp);
                    hotKey.record(key);
                    maybeExtendTtlMine(key);
                    log.info("feed.mine source=page key={} page={} size={} user={}", key, safePage, safeSize, userId);
                List<FeedItemResponse> enriched = enrich(cachedResp.items(), userId);
                return new FeedPageResponse(enriched, cachedResp.page(), cachedResp.size(), cachedResp.hasMore(), cachedResp.nextCursor());
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

        FeedPageResponse resp = new FeedPageResponse(items, safePage, safeSize, hasMore,
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
    @Override
    public FeedPageResponse getFollowingFeed(long userId, int page, int size) {
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
        return new FeedPageResponse(items, safePage, safeSize, hasMore,
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
                    all.addAll(rows);
                    continue;
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
    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

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
            List<String> tags = parseStringArray(r.getTags());
            List<String> imgs = parseStringArray(r.getImgUrls());
            String cover = imgs.isEmpty() ? null : imgs.getFirst();

            Map<String, Long> counts = counterService.getCounts("post", String.valueOf(r.getId()), List.of("like", "fav"));
            Long likeCount = counts.getOrDefault("like", 0L);
            Long favoriteCount = counts.getOrDefault("fav", 0L);

            Boolean liked = userIdNullable != null && counterService.isLiked("post", String.valueOf(r.getId()), userIdNullable);
            Boolean faved = userIdNullable != null && counterService.isFaved("post", String.valueOf(r.getId()), userIdNullable);
            Boolean isTop = includeIsTop ? r.getIsTop() : null;

            items.add(new FeedItemResponse(
                    String.valueOf(r.getId()),
                    r.getSlug(),
                    r.getTitle(),
                    r.getDescription(),
                    cover,
                    tags,
                    r.getAuthorAvatar(),
                    r.getAuthorNickname(),
                    r.getAuthorTagJson(),
                    likeCount,
                    favoriteCount,
                    liked,
                    faved,
                    isTop
            ));
        }
        return items;
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
            List<String> tags = parseStringArray(r.getTags());
            List<String> imgs = parseStringArray(r.getImgUrls());
            String cover = imgs.isEmpty() ? null : imgs.getFirst();

            Map<String, Long> counts = countsBatch.getOrDefault(String.valueOf(r.getId()), Map.of());
            Long likeCount = counts.getOrDefault("like", 0L);
            Long favoriteCount = counts.getOrDefault("fav", 0L);
            boolean liked = Boolean.TRUE.equals(likedBatch.get(r.getId()));
            boolean faved = Boolean.TRUE.equals(favBatch.get(r.getId()));

            items.add(new FeedItemResponse(
                    String.valueOf(r.getId()),
                    r.getSlug(),
                    r.getTitle(),
                    r.getDescription(),
                    cover,
                    tags,
                    r.getAuthorAvatar(),
                    r.getAuthorNickname(),
                    r.getAuthorTagJson(),
                    likeCount,
                    favoriteCount,
                    liked,
                    faved,
                    null
            ));
        }
        return items;
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
