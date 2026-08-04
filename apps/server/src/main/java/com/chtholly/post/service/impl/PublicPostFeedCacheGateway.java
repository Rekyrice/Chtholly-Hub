package com.chtholly.post.service.impl;

import com.chtholly.cache.hotkey.HotKeyDetector;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.model.PostFeedRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns the public-feed Caffeine and Redis cache layout.
 *
 * <p>This adapter deliberately returns neutral feed fragments. Current-user flags and
 * authoritative counters are applied by {@link FeedItemAssembler} after the cache read.
 */
@Component
public class PublicPostFeedCacheGateway {

    private static final Logger log = LoggerFactory.getLogger(PublicPostFeedCacheGateway.class);
    private static final String FEED_PUBLIC_PAGES_KEY = "feed:public:pages";
    private static final int LAYOUT_VERSION = 3;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Cache<String, PageResponse<FeedItemResponse>> localCache;
    private final HotKeyDetector hotKey;

    /**
     * Creates the public-feed cache gateway.
     *
     * @param redis Redis client used by the fragment cache
     * @param objectMapper feed-fragment JSON codec
     * @param localCache process-local public-feed cache
     * @param hotKey hot-key detector used to extend item TTLs
     */
    public PublicPostFeedCacheGateway(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Qualifier("feedPublicCache") Cache<String, PageResponse<FeedItemResponse>> localCache,
            HotKeyDetector hotKey) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.localCache = localCache;
        this.hotKey = hotKey;
    }

    String pageKeyByPage(int page, int size) {
        return "feed:public:" + size + ":" + page + ":v" + LAYOUT_VERSION;
    }

    String pageKeyByCursor(String cursorSlot, int size) {
        return "feed:public:" + size + ":" + cursorSlot + ":v" + LAYOUT_VERSION;
    }

    String publicPageKey(Integer page, String cursorSlot, int size, Long ownerId, String tag) {
        if (tag != null && !tag.isBlank()) {
            int safePage = page != null ? Math.max(page, 1) : 1;
            String ownerPart = ownerId != null ? String.valueOf(ownerId) : "all";
            return "feed:tag:" + tag.trim() + ":" + ownerPart + ":" + size + ":" + safePage
                    + ":v" + LAYOUT_VERSION;
        }
        if (ownerId != null) {
            int safePage = page != null ? Math.max(page, 1) : 1;
            return "feed:owner:" + ownerId + ":" + size + ":" + safePage + ":v" + LAYOUT_VERSION;
        }
        if (cursorSlot != null) {
            return pageKeyByCursor(cursorSlot, size);
        }
        int safePage = page != null ? Math.max(page, 1) : 1;
        return pageKeyByPage(safePage, size);
    }

    PageResponse<FeedItemResponse> getLocal(String pageKey) {
        return localCache.getIfPresent(pageKey);
    }

    void putLocal(String pageKey, PageResponse<FeedItemResponse> page) {
        localCache.put(pageKey, page);
    }

    CachedFeedPage readFragments(String idsKey, String hasMoreKey, int size) {
        List<String> ids = redis.opsForList().range(idsKey, 0, size - 1L);
        String hasMoreValue = redis.opsForValue().get(hasMoreKey);
        if (ids == null || ids.isEmpty()) {
            return null;
        }

        List<String> itemKeys = ids.stream().map(id -> "feed:item:" + id).toList();
        List<String> itemJsons = redis.opsForValue().multiGet(itemKeys);
        List<FeedItemResponse> items = new ArrayList<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            String itemJson = itemJsons != null && index < itemJsons.size() ? itemJsons.get(index) : null;
            if (itemJson == null) {
                return null;
            }
            try {
                FeedItemResponse item = objectMapper.readValue(itemJson, FeedItemResponse.class);
                if (item.authorId() == null || item.authorId().isBlank()) {
                    return null;
                }
                items.add(item);
            } catch (Exception failure) {
                log.debug("Public feed fragment could not be decoded, key={}", itemKeys.get(index), failure);
                return null;
            }
        }

        boolean hasMore = hasMoreValue != null ? "1".equals(hasMoreValue) : ids.size() == size;
        String nextCursor = redis.opsForValue().get(idsKey + ":nextCursor");
        return new CachedFeedPage(items, hasMore, nextCursor);
    }

    void writeCaches(
            String pageKey,
            String idsKey,
            String hasMoreKey,
            int size,
            List<PostFeedRow> rows,
            List<FeedItemResponse> items,
            boolean hasMore,
            String nextCursor,
            Duration fragmentTtl) {
        List<String> ids = rows.stream().map(row -> String.valueOf(row.getId())).toList();
        if (!ids.isEmpty()) {
            redis.delete(List.of(idsKey, hasMoreKey, idsKey + ":nextCursor"));
            redis.opsForList().leftPushAll(idsKey, ids);
            redis.expire(idsKey, fragmentTtl);
            if (ids.size() == size && hasMore) {
                redis.opsForValue().set(
                        hasMoreKey,
                        "1",
                        Duration.ofSeconds(10 + ThreadLocalRandom.current().nextInt(11)));
            } else {
                redis.opsForValue().set(hasMoreKey, hasMore ? "1" : "0", Duration.ofSeconds(10));
            }
            if (nextCursor != null) {
                redis.opsForValue().set(idsKey + ":nextCursor", nextCursor, fragmentTtl);
            }
        }

        indexPublicPage(pageKey);
        long hourSlot = System.currentTimeMillis() / 3_600_000L;
        for (FeedItemResponse item : items) {
            String indexKey = "feed:public:index:" + item.id() + ":" + hourSlot;
            redis.opsForSet().add(indexKey, pageKey);
            redis.expire(indexKey, fragmentTtl);
            try {
                redis.opsForValue().set(
                        "feed:item:" + item.id(),
                        objectMapper.writeValueAsString(item),
                        fragmentTtl);
            } catch (Exception failure) {
                log.warn("Failed to cache feed item, id={}", item.id(), failure);
            }
        }
    }

    void recordItemHotKeys(List<FeedItemResponse> items) {
        if (items == null) {
            return;
        }
        for (FeedItemResponse item : items) {
            recordItemHotKey(item.id());
        }
    }

    PageResponse<FeedItemResponse> stripUserFlags(PageResponse<FeedItemResponse> page) {
        if (page.items() == null) {
            return page;
        }
        List<FeedItemResponse> neutral = page.items().stream()
                .map(FeedItemResponse::withoutUserFlags)
                .toList();
        return PageResponse.offset(
                neutral, page.page(), page.size(), page.total(), page.hasMore(), page.nextCursor());
    }

    private void recordItemHotKey(String itemId) {
        String hotKeyId = "post:" + itemId;
        hotKey.record(hotKeyId);
        int targetTtl = hotKey.ttlForPublic(60, hotKeyId);
        String itemKey = "feed:item:" + itemId;
        Long itemTtl = redis.getExpire(itemKey);
        if (itemTtl == null || itemTtl < targetTtl) {
            redis.expire(itemKey, Duration.ofSeconds(targetTtl));
        }
    }

    private void indexPublicPage(String pageKey) {
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

    record CachedFeedPage(List<FeedItemResponse> items, boolean hasMore, String nextCursor) {
    }
}
