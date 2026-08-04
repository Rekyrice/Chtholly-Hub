package com.chtholly.relation.service;

import com.chtholly.counter.service.UserCounterService;
import com.chtholly.relation.mapper.RelationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the user counter projection, repairs stale Redis state, and overlays authoritative reaction facts.
 */
@Service
public class RelationCounterQueryService {

    private static final Logger log = LoggerFactory.getLogger(RelationCounterQueryService.class);
    private static final int COUNTER_SEGMENTS = 5;
    private static final int BYTES_PER_SEGMENT = Integer.BYTES;
    private static final int MINIMUM_PROJECTION_BYTES = COUNTER_SEGMENTS * BYTES_PER_SEGMENT;
    private static final Duration CONSISTENCY_CHECK_INTERVAL = Duration.ofSeconds(300);

    private final StringRedisTemplate redis;
    private final UserCounterService userCounterService;
    private final RelationMapper relationMapper;

    /**
     * Creates the user-counter query application service.
     *
     * @param redis Redis projection client
     * @param userCounterService counter rebuild and authoritative reaction service
     * @param relationMapper authoritative follow relationship reader
     */
    public RelationCounterQueryService(
            StringRedisTemplate redis,
            UserCounterService userCounterService,
            RelationMapper relationMapper) {
        this.redis = redis;
        this.userCounterService = userCounterService;
        this.relationMapper = relationMapper;
    }

    /**
     * Returns follow, publication, and received-reaction counters for one user.
     *
     * @param userId subject user ID
     * @return counters in the stable API field order
     */
    public Map<String, Long> getCounters(long userId) {
        byte[] raw = readProjection(userId);
        if (!isUsable(raw)) {
            rebuildSafely(userId, "empty SDS");
            raw = readProjection(userId);
            if (!isUsable(raw)) {
                return overlayAuthoritativeReactionCounters(emptyCounters(), userId);
            }
        }

        int segments = raw.length / BYTES_PER_SEGMENT;
        long sdsFollowings = readSegment(raw, 1);
        long sdsFollowers = readSegment(raw, 2);

        if (shouldRunConsistencyCheck(userId)) {
            int databaseFollowings = countFollowingSafely(userId);
            int databaseFollowers = countFollowersSafely(userId);
            if (segments != COUNTER_SEGMENTS
                    || sdsFollowings != databaseFollowings
                    || sdsFollowers != databaseFollowers) {
                rebuildSafely(userId, "SDS mismatch");
                byte[] rebuilt = readProjection(userId);
                if (isUsable(rebuilt)) {
                    return overlayAuthoritativeReactionCounters(project(rebuilt), userId);
                }
            }
        }

        return overlayAuthoritativeReactionCounters(project(raw), userId);
    }

    private byte[] readProjection(long userId) {
        return redis.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(counterKey(userId).getBytes(StandardCharsets.UTF_8)));
    }

    private boolean shouldRunConsistencyCheck(long userId) {
        Boolean acquired = redis.opsForValue().setIfAbsent(
                "ucnt:chk:" + userId,
                "1",
                CONSISTENCY_CHECK_INTERVAL);
        return Boolean.TRUE.equals(acquired);
    }

    private int countFollowingSafely(long userId) {
        try {
            return relationMapper.countFollowingActive(userId);
        } catch (Exception failure) {
            log.warn("Count following active failed, userId={}: {}",
                    userId, failure.getMessage(), failure);
            return 0;
        }
    }

    private int countFollowersSafely(long userId) {
        try {
            return relationMapper.countFollowerActive(userId);
        } catch (Exception failure) {
            log.warn("Count follower active failed, userId={}: {}",
                    userId, failure.getMessage(), failure);
            return 0;
        }
    }

    private void rebuildSafely(long userId, String reason) {
        try {
            userCounterService.rebuildAllCounters(userId);
        } catch (Exception failure) {
            log.warn("User counter rebuild failed during {}, userId={}: {}",
                    reason, userId, failure.getMessage(), failure);
        }
    }

    private Map<String, Long> project(byte[] raw) {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("followings", readSegment(raw, 1));
        counters.put("followers", readSegment(raw, 2));
        counters.put("posts", readSegment(raw, 3));
        counters.put("likedPosts", readSegment(raw, 4));
        counters.put("favedPosts", readSegment(raw, 5));
        return counters;
    }

    private Map<String, Long> emptyCounters() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("followings", 0L);
        counters.put("followers", 0L);
        counters.put("posts", 0L);
        counters.put("likedPosts", 0L);
        counters.put("favedPosts", 0L);
        return counters;
    }

    private Map<String, Long> overlayAuthoritativeReactionCounters(Map<String, Long> counters, long userId) {
        counters.put("likedPosts", userCounterService.countLikesReceived(userId));
        counters.put("favedPosts", userCounterService.countFavsReceived(userId));
        return counters;
    }

    private static String counterKey(long userId) {
        return "ucnt:" + userId;
    }

    private static boolean isUsable(byte[] raw) {
        return raw != null && raw.length >= MINIMUM_PROJECTION_BYTES;
    }

    private static long readSegment(byte[] raw, int index) {
        int segments = raw.length / BYTES_PER_SEGMENT;
        if (index < 1 || index > segments) {
            return 0L;
        }
        int offset = (index - 1) * BYTES_PER_SEGMENT;
        long value = 0L;
        for (int byteIndex = 0; byteIndex < BYTES_PER_SEGMENT; byteIndex++) {
            value = (value << Byte.SIZE) | (raw[offset + byteIndex] & 0xFFL);
        }
        return value;
    }
}
