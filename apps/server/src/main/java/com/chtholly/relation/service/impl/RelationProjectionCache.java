package com.chtholly.relation.service.impl;

import com.chtholly.relation.mapper.RelationMapper;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/** Reads and rebuilds relation list projections across L1, Redis ZSet, and MySQL. */
@Service
public class RelationProjectionCache {

    private final RelationMapper relationMapper;
    private final StringRedisTemplate redis;
    private final RelationCacheInvalidator relationCacheInvalidator;

    /** Creates the relation projection cache gateway. */
    public RelationProjectionCache(
            RelationMapper relationMapper,
            StringRedisTemplate redis,
            RelationCacheInvalidator relationCacheInvalidator) {
        this.relationMapper = relationMapper;
        this.redis = redis;
        this.relationCacheInvalidator = relationCacheInvalidator;
    }

    /** Reads following identifiers using offset pagination. */
    public List<Long> following(long userId, int limit, int offset) {
        return getListWithOffset(
                followingKey(userId),
                offset,
                limit,
                need -> relationMapper.listFollowingRows(userId, need, 0),
                "toUserId",
                "createdAt",
                true,
                userId);
    }

    /** Reads follower identifiers using offset pagination. */
    public List<Long> followers(long userId, int limit, int offset) {
        return getListWithOffset(
                followersKey(userId),
                offset,
                limit,
                need -> relationMapper.listFollowerRows(userId, need, 0),
                "fromUserId",
                "createdAt",
                false,
                userId);
    }

    /** Reads following identifiers using the legacy millisecond cursor. */
    public List<Long> followingCursor(
            long userId,
            int limit,
            Long cursor) {
        return getListWithCursor(
                followingKey(userId),
                limit,
                cursor,
                need -> relationMapper.listFollowingRows(userId, need, 0),
                "toUserId",
                "createdAt");
    }

    /** Reads follower identifiers using the legacy millisecond cursor. */
    public List<Long> followersCursor(
            long userId,
            int limit,
            Long cursor) {
        return getListWithCursor(
                followersKey(userId),
                limit,
                cursor,
                need -> relationMapper.listFollowerRows(userId, need, 0),
                "fromUserId",
                "createdAt");
    }

    /** Returns the current projection score used to construct the next cursor. */
    public Double score(boolean following, long userId, long relatedUserId) {
        return redis.opsForZSet().score(
                following ? followingKey(userId) : followersKey(userId),
                String.valueOf(relatedUserId));
    }

    private List<Long> getListWithOffset(
            String key,
            int offset,
            int limit,
            IntFunction<Map<Long, Map<String, Object>>> rowsFetcher,
            String idField,
            String tsField,
            boolean followingList,
            long userId) {
        List<Long> top = followingList
                ? relationCacheInvalidator.getFollowingTop(userId)
                : relationCacheInvalidator.getFollowerTop(userId);
        List<Long> result = new ArrayList<>(limit);
        int projectionOffset = offset;
        if (top != null && !top.isEmpty() && offset < top.size()) {
            int to = Math.min(offset + limit, top.size());
            result.addAll(top.subList(offset, to));
            if (result.size() == limit) {
                return result;
            }
            projectionOffset = to;
        }

        Set<String> cached = redis.opsForZSet().reverseRange(
                key,
                projectionOffset,
                projectionOffset + (limit - result.size()) - 1L);
        if (cached != null && cached.size() >= limit - result.size()) {
            result.addAll(toLongList(cached));
            return result;
        }

        int need = Math.max(1, limit + offset);
        Map<Long, Map<String, Object>> rows =
                rowsFetcher.apply(Math.min(need, 1000));
        if (rows == null || rows.isEmpty()) {
            if (cached != null) {
                result.addAll(toLongList(cached));
            }
            return result;
        }

        fillZSet(key, rows, idField, tsField, null);
        redis.expire(key, Duration.ofHours(2));
        if (isBigV(userId)) {
            maybeUpdateTopCache(userId, key, followingList);
        }
        Set<String> filled = redis.opsForZSet().reverseRange(
                key,
                projectionOffset,
                projectionOffset + (limit - result.size()) - 1L);
        if (filled != null) {
            result.addAll(toLongList(filled));
        }
        return result;
    }

    private List<Long> getListWithCursor(
            String key,
            int limit,
            Long cursor,
            IntFunction<Map<Long, Map<String, Object>>> rowsFetcher,
            String idField,
            String tsField) {
        double max = cursor == null
                ? Double.POSITIVE_INFINITY
                : cursor.doubleValue();
        Set<String> cached = redis.opsForZSet().reverseRangeByScore(
                key, Double.NEGATIVE_INFINITY, max, 0, limit);
        if (cached != null && !cached.isEmpty()) {
            return toLongList(cached);
        }

        int need = Math.max(limit, 100);
        Map<Long, Map<String, Object>> rows =
                rowsFetcher.apply(Math.min(need, 1000));
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        fillZSet(key, rows, idField, tsField, cursor);
        redis.expire(key, Duration.ofHours(2));
        Set<String> filled = redis.opsForZSet().reverseRangeByScore(
                key, Double.NEGATIVE_INFINITY, max, 0, limit);
        return filled == null
                ? Collections.emptyList()
                : toLongList(filled);
    }

    private void fillZSet(
            String key,
            Map<Long, Map<String, Object>> rows,
            String idField,
            String tsField,
            Long cursor) {
        for (Map<String, Object> row : rows.values()) {
            Object id = row.get(idField);
            Object timestamp = row.get(tsField);
            if (id == null || timestamp == null) {
                continue;
            }
            long score = timestampScore(timestamp);
            if (cursor == null || score <= cursor) {
                redis.opsForZSet().add(key, String.valueOf(id), score);
            }
        }
    }

    private long timestampScore(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.getTime();
        }
        if (value instanceof Date date) {
            return date.getTime();
        }
        return System.currentTimeMillis();
    }

    private List<Long> toLongList(Set<String> values) {
        List<Long> result = new ArrayList<>(values.size());
        for (String value : values) {
            result.add(Long.valueOf(value));
        }
        return result;
    }

    private boolean isBigV(long userId) {
        byte[] raw = redis.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(
                        ("ucnt:" + userId).getBytes(StandardCharsets.UTF_8)));
        if (raw == null || raw.length < 20) {
            return false;
        }
        long followers = 0;
        int offset = 2 * 4;
        for (int i = 0; i < 4; i++) {
            followers = (followers << 8) | (raw[offset + i] & 0xFFL);
        }
        return followers >= 500_000L;
    }

    private void maybeUpdateTopCache(
            long userId,
            String key,
            boolean followingList) {
        Set<String> values = redis.opsForZSet().reverseRange(key, 0, 499);
        if (values == null || values.isEmpty()) {
            return;
        }
        List<Long> ids = toLongList(values);
        if (followingList) {
            relationCacheInvalidator.putFollowingTop(userId, ids);
        } else {
            relationCacheInvalidator.putFollowerTop(userId, ids);
        }
    }

    private static String followingKey(long userId) {
        return "uf:flws:" + userId;
    }

    private static String followersKey(long userId) {
        return "uf:fans:" + userId;
    }
}
