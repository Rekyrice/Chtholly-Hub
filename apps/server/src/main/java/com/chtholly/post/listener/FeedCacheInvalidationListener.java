package com.chtholly.post.listener;

import com.chtholly.counter.event.CounterEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.model.Post;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.chtholly.common.job.CleanupProperties;
import org.springframework.data.redis.connection.DataType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Feed 页面缓存失效与计数旁路更新监听器。
 *
 * <p>职责：</p>
 * - 监听点赞/收藏等计数事件（仅处理实体类型为 "post"）；
 * - 根据“页面反向索引”（`feed:public:index:{eid}:{hour}`）定位受影响页面，
 *   幂等失效本地 Caffeine 与 Redis 页面缓存；
 * - 失效创作者用户计数缓存，读侧从 MySQL 互动事实恢复 reaction 字段。
 *
 * <p>设计要点：</p>
 * - 不在乱序重试事件上执行有下限的增量覆盖，避免永久计数漂移；
 * - 反向索引按小时维护，监听器会同时覆盖当前与上一个小时段的页面键。
 */
@Component
public class FeedCacheInvalidationListener {

    private static final Logger log = LoggerFactory.getLogger(FeedCacheInvalidationListener.class);

    private static final String FEED_PUBLIC_PAGES_KEY = "feed:public:pages";

    private final Cache<String, PageResponse<FeedItemResponse>> feedPublicCache;
    private final StringRedisTemplate redis;
    private final com.chtholly.counter.service.UserCounterService userCounterService;
    private final com.chtholly.post.mapper.PostMapper postMapper;
    private final CleanupProperties cleanupProperties;

    public FeedCacheInvalidationListener(@Qualifier("feedPublicCache") Cache<String, PageResponse<FeedItemResponse>> feedPublicCache,
                                         StringRedisTemplate redis,
                                         com.chtholly.counter.service.UserCounterService userCounterService,
                                         com.chtholly.post.mapper.PostMapper postMapper,
                                         CleanupProperties cleanupProperties) {
        this.feedPublicCache = feedPublicCache;
        this.redis = redis;
        this.userCounterService = userCounterService;
        this.postMapper = postMapper;
        this.cleanupProperties = cleanupProperties;
    }

    /**
     * 监听计数事件并进行缓存更新。
     *
     * <p>流程：</p>
     * - 仅处理实体类型为 "post" 的 like/fav 事件；
     * - 若可解析到内容的创作者 ID，则失效其用户计数缓存；
     * - 通过最近两小时的反向索引集合定位受影响页面：
     *   - 失效本地 Caffeine 页缓存；
     *   - 删除 Redis 页缓存并清理反向索引引用。
     */
    @EventListener
    public void onCounterChanged(CounterEvent event) {
        if (!"post".equals(event.getEntityType())) {
            return;
        }

        String metric = event.getMetric();
        if ("like".equals(metric) || "fav".equals(metric)) {
            String eid = event.getEntityId();

            try {
                Long owner = event.getPostCreatorId();
                if (owner == null) {
                    Post post = postMapper.findById(Long.valueOf(eid));
                    if (post != null && post.getCreatorId() != null) {
                        owner = post.getCreatorId();
                    }
                }
                if (owner != null) {
                    userCounterService.invalidateReactionCounters(owner);
                }
            } catch (Exception e) {
                log.warn("Feed cache counter side-effect failed, eid={}: {}", eid, e.getMessage());
            }

            long hourSlot = System.currentTimeMillis() / 3600000L;
            Set<String> keys = new LinkedHashSet<>();
            Set<String> cur = redis.opsForSet().members("feed:public:index:" + eid + ":" + hourSlot);
            if (cur != null) {
                keys.addAll(cur);
            }

            Set<String> prev = redis.opsForSet().members("feed:public:index:" + eid + ":" + (hourSlot - 1));
            if (prev != null) {
                keys.addAll(prev);
            }
            if (keys.isEmpty()) {
                return;
            }

            for (String key : keys) {
                feedPublicCache.invalidate(key);
                redis.delete(key);
                redis.opsForSet().remove("feed:public:index:" + eid + ":" + hourSlot, key);
                redis.opsForSet().remove(
                        "feed:public:index:" + eid + ":" + (hourSlot - 1), key);
            }
        }
    }

    /**
     * 每小时检查 feed 页面索引容量，超出上限时移除最旧条目。
     */
    @Scheduled(cron = "0 0 * * * *")
    public void trimFeedPublicPagesIndex() {
        int maxSize = cleanupProperties.feedPages().maxSize();
        long removed = trimFeedPublicPagesIndex(maxSize);
        if (removed > 0) {
            log.info("[Cleanup] feed:public:pages: removed {} entries, maxSize={}", removed, maxSize);
        }
    }

    long trimFeedPublicPagesIndex(int maxSize) {
        DataType type = redis.type(FEED_PUBLIC_PAGES_KEY);
        if (type == DataType.SET) {
            return trimLegacySetIndex(maxSize);
        }
        if (type == DataType.ZSET || type == DataType.NONE) {
            return trimSortedSetIndex(maxSize);
        }
        log.warn("[Cleanup] feed:public:pages has unexpected type {}, skip trim", type);
        return 0L;
    }

    private long trimSortedSetIndex(int maxSize) {
        Long size = redis.opsForZSet().size(FEED_PUBLIC_PAGES_KEY);
        if (size == null || size <= maxSize) {
            return 0L;
        }
        long removeCount = size - maxSize;
        Long removed = redis.opsForZSet().removeRange(FEED_PUBLIC_PAGES_KEY, 0, removeCount - 1);
        return removed != null ? removed : 0L;
    }

    private long trimLegacySetIndex(int maxSize) {
        Long size = redis.opsForSet().size(FEED_PUBLIC_PAGES_KEY);
        if (size == null || size <= maxSize) {
            return 0L;
        }
        long removeCount = size - maxSize;
        long removed = 0L;
        for (long i = 0; i < removeCount; i++) {
            String member = redis.opsForSet().pop(FEED_PUBLIC_PAGES_KEY);
            if (member == null) {
                break;
            }
            removed++;
        }
        return removed;
    }
}
