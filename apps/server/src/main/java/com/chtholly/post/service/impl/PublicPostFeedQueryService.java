package com.chtholly.post.service.impl;

import com.chtholly.cache.config.CacheProperties;
import com.chtholly.cache.observability.CacheMetrics;
import com.chtholly.cache.singleflight.SingleFlightLockRegistry;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostFeedRow;
import com.chtholly.post.util.FeedCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Coordinates public-feed routing, origin reads, cache policy, and response assembly. */
@Service
public class PublicPostFeedQueryService {

    private static final Logger log = LoggerFactory.getLogger(PublicPostFeedQueryService.class);

    private final PostMapper mapper;
    private final PublicPostFeedCacheGateway cacheGateway;
    private final FeedItemAssembler assembler;
    private final CacheProperties.ReadMode readMode;
    private final CacheMetrics cacheMetrics;
    private final SingleFlightLockRegistry singleFlight = new SingleFlightLockRegistry();

    /**
     * Creates the public-feed query coordinator.
     *
     * @param mapper public-feed persistence mapper
     * @param cacheGateway public-feed cache adapter
     * @param assembler feed-item assembler
     * @param cacheProperties configured cache read policy
     * @param cacheMetrics cache-origin observability recorder
     */
    public PublicPostFeedQueryService(
            PostMapper mapper,
            PublicPostFeedCacheGateway cacheGateway,
            FeedItemAssembler assembler,
            CacheProperties cacheProperties,
            CacheMetrics cacheMetrics) {
        this.mapper = mapper;
        this.cacheGateway = cacheGateway;
        this.assembler = assembler;
        this.readMode = cacheProperties.getReadMode();
        this.cacheMetrics = cacheMetrics;
    }

    PageResponse<FeedItemResponse> getPublicFeed(
            Integer page,
            String cursor,
            int size,
            Long ownerId,
            String tag,
            Long currentUserId) {
        if (tag != null && !tag.isBlank()) {
            int safePage = page != null ? Math.max(page, 1) : 1;
            return getByTag(tag.trim(), ownerId, safePage, size, currentUserId);
        }
        if (ownerId != null) {
            int safePage = page != null ? Math.max(page, 1) : 1;
            return getByOwner(ownerId, safePage, size, currentUserId);
        }
        if (cursor != null) {
            return getByCursor(cursor, size, currentUserId);
        }
        int safePage = page != null ? Math.max(page, 1) : 1;
        return getByOffset(safePage, size, currentUserId);
    }

    String publicFeedPageKey(Integer page, String cursor, int size, Long ownerId, String tag) {
        int safeSize = clampSize(size);
        String cursorSlot = cursor == null ? null : FeedCursor.cacheSlot(cursor);
        return cacheGateway.publicPageKey(page, cursorSlot, safeSize, ownerId, tag);
    }

    private PageResponse<FeedItemResponse> getByOffset(int page, int size, Long currentUserId) {
        int safeSize = clampSize(size);
        String localPageKey = cacheGateway.pageKeyByPage(page, safeSize);
        long hourSlot = System.currentTimeMillis() / 3_600_000L;
        String idsKey = "feed:public:ids:" + safeSize + ":" + hourSlot + ":" + page;
        String hasMoreKey = idsKey + ":hasMore";

        if (!readMode.usesCache()) {
            return loadOffsetFromDatabase(
                    localPageKey, idsKey, hasMoreKey, page, safeSize, currentUserId, false);
        }

        PageResponse<FeedItemResponse> local = cacheGateway.getLocal(localPageKey);
        if (local != null && local.items() != null) {
            cacheGateway.recordItemHotKeys(local.items());
            log.info("feed.public source=local localPageKey={} page={} size={}", localPageKey, page, safeSize);
            return page(assembler.enrich(local.items(), currentUserId), page, safeSize,
                    local.hasMore(), local.nextCursor());
        }

        PageResponse<FeedItemResponse> cached = readFragments(idsKey, hasMoreKey, page, safeSize, currentUserId);
        if (cached != null) {
            cacheGateway.putLocal(localPageKey, cached);
            cacheGateway.recordItemHotKeys(cached.items());
            log.info("feed.public source=3tier localPageKey={} page={} size={}", localPageKey, page, safeSize);
            return cached;
        }

        Supplier<PageResponse<FeedItemResponse>> loader = () -> {
            PageResponse<FeedItemResponse> afterFlight =
                    readFragments(idsKey, hasMoreKey, page, safeSize, currentUserId);
            if (afterFlight != null) {
                cacheGateway.putLocal(localPageKey, afterFlight);
                cacheGateway.recordItemHotKeys(afterFlight.items());
                log.info("feed.public source=3tier(after-flight) localPageKey={} page={} size={}",
                        localPageKey, page, safeSize);
                return afterFlight;
            }
            return loadOffsetFromDatabase(
                    localPageKey, idsKey, hasMoreKey, page, safeSize, currentUserId, true);
        };
        return readMode.usesSingleFlight() ? singleFlight.runExclusive(idsKey, loader) : loader.get();
    }

    private PageResponse<FeedItemResponse> loadOffsetFromDatabase(
            String localPageKey,
            String idsKey,
            String hasMoreKey,
            int page,
            int size,
            Long currentUserId,
            boolean populateCache) {
        cacheMetrics.recordSameKeyLoad();
        cacheMetrics.recordMysqlQuery();
        List<PostFeedRow> rows = mapper.listFeedPublic(size + 1, (page - 1) * size);
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }
        List<FeedItemResponse> items = assembler.mapRows(rows, null, false);
        String nextCursor = hasMore ? nextCursorFromRows(rows) : null;
        if (populateCache) {
            PageResponse<FeedItemResponse> cachePage = page(items, page, size, hasMore, nextCursor);
            cacheGateway.writeCaches(localPageKey, idsKey, hasMoreKey, size, rows, items,
                    hasMore, nextCursor, fragmentTtl());
            cacheGateway.putLocal(localPageKey, cachePage);
        }
        List<FeedItemResponse> enriched = assembler.enrich(items, currentUserId);
        log.info("feed.public source=db localPageKey={} page={} size={} hasMore={}",
                localPageKey, page, size, hasMore);
        return page(enriched, page, size, hasMore, nextCursor);
    }

    private PageResponse<FeedItemResponse> getByCursor(String cursor, int size, Long currentUserId) {
        int safeSize = clampSize(size);
        String cursorSlot = FeedCursor.cacheSlot(cursor);
        String localPageKey = cacheGateway.pageKeyByCursor(cursorSlot, safeSize);
        long hourSlot = System.currentTimeMillis() / 3_600_000L;
        String idsKey = "feed:public:ids:" + safeSize + ":" + hourSlot + ":" + cursorSlot;
        String hasMoreKey = idsKey + ":hasMore";

        if (!readMode.usesCache()) {
            return loadCursorFromDatabase(
                    cursor, cursorSlot, localPageKey, idsKey, hasMoreKey, safeSize, currentUserId, false);
        }

        PageResponse<FeedItemResponse> local = cacheGateway.getLocal(localPageKey);
        if (local != null && local.items() != null) {
            cacheGateway.recordItemHotKeys(local.items());
            log.info("feed.public source=local cursor={} size={}", cursorSlot, safeSize);
            return page(assembler.enrich(local.items(), currentUserId), 0, safeSize,
                    local.hasMore(), local.nextCursor());
        }

        PageResponse<FeedItemResponse> cached = readFragments(idsKey, hasMoreKey, 0, safeSize, currentUserId);
        if (cached != null) {
            cacheGateway.putLocal(localPageKey, cacheGateway.stripUserFlags(cached));
            cacheGateway.recordItemHotKeys(cached.items());
            log.info("feed.public source=3tier cursor={} size={}", cursorSlot, safeSize);
            return cached;
        }

        Supplier<PageResponse<FeedItemResponse>> loader = () -> {
            PageResponse<FeedItemResponse> afterFlight =
                    readFragments(idsKey, hasMoreKey, 0, safeSize, currentUserId);
            if (afterFlight != null) {
                cacheGateway.putLocal(localPageKey, cacheGateway.stripUserFlags(afterFlight));
                cacheGateway.recordItemHotKeys(afterFlight.items());
                return afterFlight;
            }
            return loadCursorFromDatabase(
                    cursor, cursorSlot, localPageKey, idsKey, hasMoreKey, safeSize, currentUserId, true);
        };
        return readMode.usesSingleFlight() ? singleFlight.runExclusive(idsKey, loader) : loader.get();
    }

    private PageResponse<FeedItemResponse> loadCursorFromDatabase(
            String cursor,
            String cursorSlot,
            String localPageKey,
            String idsKey,
            String hasMoreKey,
            int size,
            Long currentUserId,
            boolean populateCache) {
        cacheMetrics.recordSameKeyLoad();
        cacheMetrics.recordMysqlQuery();
        List<PostFeedRow> rows;
        if (cursor == null || cursor.isBlank()) {
            rows = mapper.listFeedPublic(size + 1, 0);
        } else {
            FeedCursor.FeedCursorPoint point = FeedCursor.require(cursor);
            rows = mapper.listFeedPublicByCursor(point.publishTime(), point.postId(), size + 1);
        }
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }
        List<FeedItemResponse> items = assembler.mapRows(rows, null, false);
        String nextCursor = hasMore ? nextCursorFromRows(rows) : null;
        if (populateCache) {
            PageResponse<FeedItemResponse> cachePage = page(items, 0, size, hasMore, nextCursor);
            cacheGateway.writeCaches(localPageKey, idsKey, hasMoreKey, size, rows, items,
                    hasMore, nextCursor, fragmentTtl());
            cacheGateway.putLocal(localPageKey, cachePage);
        }
        List<FeedItemResponse> enriched = assembler.enrich(items, currentUserId);
        log.info("feed.public source=db cursor={} size={} hasMore={}", cursorSlot, size, hasMore);
        return page(enriched, 0, size, hasMore, nextCursor);
    }

    private PageResponse<FeedItemResponse> getByOwner(
            long ownerId, int page, int size, Long currentUserId) {
        int safeSize = clampSize(size);
        int safePage = Math.max(page, 1);
        List<PostFeedRow> rows = mapper.listFeedPublicByCreator(
                ownerId, safeSize + 1, (safePage - 1) * safeSize);
        boolean hasMore = rows.size() > safeSize;
        if (hasMore) {
            rows = rows.subList(0, safeSize);
        }
        List<FeedItemResponse> items = assembler.fromPublicRows(rows, currentUserId, true);
        String nextCursor = hasMore ? nextCursorFromRows(rows) : null;
        log.info("feed.public source=db ownerId={} page={} size={} hasMore={}",
                ownerId, safePage, safeSize, hasMore);
        return page(items, safePage, safeSize, hasMore, nextCursor);
    }

    private PageResponse<FeedItemResponse> getByTag(
            String tag, Long ownerId, int page, int size, Long currentUserId) {
        int safeSize = clampSize(size);
        int safePage = Math.max(page, 1);
        List<PostFeedRow> rows = mapper.listFeedPublicByTag(
                tag, ownerId, safeSize + 1, (safePage - 1) * safeSize);
        boolean hasMore = rows.size() > safeSize;
        if (hasMore) {
            rows = rows.subList(0, safeSize);
        }
        List<FeedItemResponse> items = assembler.fromPublicRows(rows, currentUserId, false);
        String nextCursor = hasMore ? nextCursorFromRows(rows) : null;
        log.info("feed.public source=db tag={} ownerId={} page={} size={} hasMore={}",
                tag, ownerId, safePage, safeSize, hasMore);
        return page(items, safePage, safeSize, hasMore, nextCursor);
    }

    private PageResponse<FeedItemResponse> readFragments(
            String idsKey, String hasMoreKey, int page, int size, Long currentUserId) {
        PublicPostFeedCacheGateway.CachedFeedPage cached =
                cacheGateway.readFragments(idsKey, hasMoreKey, size);
        if (cached == null) {
            return null;
        }
        List<FeedItemResponse> items = assembler.fromCached(cached.items(), currentUserId);
        return page(items, page, size, cached.hasMore(), cached.nextCursor());
    }

    private static PageResponse<FeedItemResponse> page(
            List<FeedItemResponse> items,
            int page,
            int size,
            boolean hasMore,
            String nextCursor) {
        return PageResponse.offset(items, page, size, 0L, hasMore, nextCursor);
    }

    private static String nextCursorFromRows(List<PostFeedRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        PostFeedRow last = rows.getLast();
        if (last.getPublishTime() == null || last.getId() == null) {
            return null;
        }
        return FeedCursor.encode(last.getPublishTime(), last.getId());
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), 50);
    }

    private static Duration fragmentTtl() {
        return Duration.ofSeconds(60 + ThreadLocalRandom.current().nextInt(30));
    }
}
