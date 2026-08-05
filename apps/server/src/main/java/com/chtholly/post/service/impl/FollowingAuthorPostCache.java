package com.chtholly.post.service.impl;

import com.chtholly.post.feed.FeedTimelineProperties;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostFeedRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Owns bounded author-scoped Redis snapshots used by the large-author pull path. */
@Component
public class FollowingAuthorPostCache {

    private static final Logger log = LoggerFactory.getLogger(FollowingAuthorPostCache.class);

    private final PostMapper mapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FeedTimelineProperties properties;

    /**
     * Creates the large-author post snapshot cache.
     *
     * @param mapper authoritative post reader
     * @param redis Redis cache client
     * @param objectMapper cached payload codec
     * @param properties following-feed retention and cache configuration
     */
    public FollowingAuthorPostCache(
            PostMapper mapper,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            FeedTimelineProperties properties) {
        this.mapper = mapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    List<PostFeedRow> recentPosts(List<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Collections.emptyList();
        }
        int pullHours = properties.getTimeline().getBigvPullHours();
        int cacheSeconds = properties.getTimeline().getBigvCacheSeconds();
        Instant since = Instant.now().minus(pullHours, ChronoUnit.HOURS);

        List<PostFeedRow> all = new ArrayList<>();
        for (Long authorId : authorIds) {
            if (authorId == null) {
                continue;
            }
            String cacheKey = cacheKey(authorId);
            String cached;
            try {
                cached = redis.opsForValue().get(cacheKey);
            } catch (RuntimeException failure) {
                throw new FollowingFeedProjectionUnavailableException(
                        "bigv-post-cache", failure);
            }
            if (cached != null) {
                try {
                    List<PostFeedRow> rows = objectMapper.readValue(cached, new TypeReference<>() {});
                    if (rows != null && rows.stream()
                            .allMatch(row -> row != null && row.getAuthorId() != null)) {
                        all.addAll(rows);
                        continue;
                    }
                    log.debug("feed.following bigv cache layout miss authorId={}", authorId);
                } catch (Exception failure) {
                    log.debug("feed.following bigv cache parse miss authorId={}", authorId);
                }
            }

            List<PostFeedRow> rows = mapper.listRecentPublicByCreators(List.of(authorId), since, 50);
            if (rows == null) {
                throw new IllegalStateException("Author-scoped following-feed query returned null");
            }
            try {
                redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(rows),
                        Duration.ofSeconds(cacheSeconds));
            } catch (Exception failure) {
                log.warn("feed.following bigv cache write failed authorId={}: {}",
                        authorId, failure.getMessage());
            }
            all.addAll(rows);
        }
        return all;
    }

    void invalidate(long authorId) {
        try {
            invalidateStrict(authorId);
        } catch (RuntimeException failure) {
            log.warn("feed.following bigv cache invalidate failed authorId={}: {}",
                    authorId, failure.getMessage(), failure);
        }
    }

    void invalidateStrict(long authorId) {
        redis.delete(cacheKey(authorId));
    }

    static String cacheKey(long authorId) {
        return "feed:bigv:posts:" + authorId;
    }
}
