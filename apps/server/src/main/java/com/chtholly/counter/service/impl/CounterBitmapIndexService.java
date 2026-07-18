package com.chtholly.counter.service.impl;

import com.chtholly.counter.mapper.CounterEntityIdentity;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Maintains recoverable Bitmap shard indexes and a persistent calibration rotation. */
@Service
public class CounterBitmapIndexService {

    private static final String INDEX_VERSION = "v1";
    static final String SHARD_INDEX_SENTINEL = "@v1";

    private final StringRedisTemplate redis;
    private final int scanCount;
    private final DefaultRedisScript<List> discoveryScript;
    private final DefaultRedisScript<Long> rotateCandidateScript;

    public CounterBitmapIndexService(
            StringRedisTemplate redis,
            @Value("${counter.calibration.scan-count:500}") int scanCount) {
        this.redis = Objects.requireNonNull(redis, "redis");
        if (scanCount < 1 || scanCount > 10_000) {
            throw new IllegalArgumentException("scanCount must be between 1 and 10000");
        }
        this.scanCount = scanCount;
        this.discoveryScript = new DefaultRedisScript<>();
        this.discoveryScript.setResultType(List.class);
        this.discoveryScript.setLocation(new ClassPathResource("lua/counter/bitmap-index-discover.lua"));
        this.rotateCandidateScript = new DefaultRedisScript<>();
        this.rotateCandidateScript.setResultType(Long.class);
        this.rotateCandidateScript.setLocation(
                new ClassPathResource("lua/counter/bitmap-candidate-rotate.lua"));
    }

    /** Advances one Redis SCAN cursor page and returns at most {@code limit} safe candidates. */
    public List<CounterEntityIdentity> discoverCandidates(int limit) {
        if (limit < 0 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be between 0 and 1000");
        }
        DiscoveryResult result = executeDiscovery(limit);
        return result.complete() ? result.candidates() : List.of();
    }

    /** Whether the initial bounded pass has observed the complete Bitmap keyspace. */
    public boolean isBackfillComplete() {
        return INDEX_VERSION.equals(redis.opsForValue().get(
                CounterKeys.bitmapIndexBackfillCompleteKey()));
    }

    /** Returns the complete shard index, advancing one legacy backfill page when necessary. */
    public Set<String> requireShardKeys(String metric, String entityType, String entityId) {
        requireMetric(metric);
        CounterSchema.requirePersistableIdentity(entityType, entityId);
        if (!isBackfillComplete() && !executeDiscovery(0).complete()) {
            throw new IllegalStateException("Counter Bitmap shard index backfill is incomplete");
        }
        String indexKey = CounterKeys.bitmapShardIndexKey(metric, entityType, entityId);
        Set<String> members = redis.opsForSet().members(indexKey);
        if (members == null) {
            throw new IllegalStateException("Counter Bitmap shard index could not be read");
        }
        if (!members.contains(SHARD_INDEX_SENTINEL)) {
            Double score = redis.opsForZSet().score(
                    CounterKeys.bitmapCalibrationCandidatesKey(),
                    member(new CounterEntityIdentity(entityType, entityId)));
            if (score != null) {
                throw new IllegalStateException("Counter Bitmap shard index is missing");
            }
            if (!members.isEmpty()) {
                throw new IllegalStateException("Counter Bitmap shard index is incomplete");
            }
            return Set.of();
        }
        LinkedHashSet<String> validated = new LinkedHashSet<>();
        String prefix = "bm:" + metric + ":" + entityType + ":" + entityId + ":";
        for (String member : members) {
            if (SHARD_INDEX_SENTINEL.equals(member)) { continue; }
            if (member == null || !member.startsWith(prefix)) {
                throw new IllegalStateException("Counter Bitmap shard index contains an invalid key");
            }
            String chunk = member.substring(prefix.length());
            if (chunk.isEmpty() || !chunk.chars().allMatch(Character::isDigit)) {
                throw new IllegalStateException("Counter Bitmap shard index contains an invalid key");
            }
            validated.add(member);
        }
        return Set.copyOf(validated);
    }

    /** Moves an attempted Redis candidate to the tail; absent MySQL-only candidates are ignored. */
    public void rotateCandidate(CounterEntityIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        CounterSchema.requirePersistableIdentity(identity.entityType(), identity.entityId());
        redis.execute(
                rotateCandidateScript,
                List.of(CounterKeys.bitmapCalibrationCandidatesKey()),
                member(identity));
    }

    private DiscoveryResult executeDiscovery(int limit) {
        List<?> raw = redis.execute(
                discoveryScript,
                List.of(
                        CounterKeys.bitmapIndexBackfillCursorKey(),
                        CounterKeys.bitmapIndexBackfillCompleteKey(),
                        CounterKeys.bitmapCalibrationCandidatesKey()),
                String.valueOf(scanCount),
                String.valueOf(limit),
                SHARD_INDEX_SENTINEL);
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("Counter Bitmap discovery returned an invalid result");
        }
        String status = redisText(raw.get(0));
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new IllegalStateException("Counter Bitmap discovery returned an invalid status");
        }
        LinkedHashSet<CounterEntityIdentity> candidates = new LinkedHashSet<>();
        for (int index = 1; index < raw.size(); index++) {
            CounterEntityIdentity identity = parseMember(redisText(raw.get(index)));
            if (identity == null) {
                throw new IllegalStateException("Counter Bitmap discovery returned an invalid candidate");
            }
            candidates.add(identity);
        }
        if (candidates.size() > limit) {
            throw new IllegalStateException("Counter Bitmap discovery exceeded the candidate limit");
        }
        return new DiscoveryResult("1".equals(status), List.copyOf(candidates));
    }

    private static String redisText(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("Counter Bitmap discovery returned a non-text value");
    }

    private static String member(CounterEntityIdentity identity) {
        return identity.entityType() + ":" + identity.entityId();
    }

    private static CounterEntityIdentity parseMember(String member) {
        int separator = member.indexOf(':');
        if (separator <= 0 || separator == member.length() - 1 || member.indexOf(':', separator + 1) >= 0) {
            return null;
        }
        String entityType = member.substring(0, separator);
        String entityId = member.substring(separator + 1);
        try {
            CounterSchema.requirePersistableIdentity(entityType, entityId);
            return new CounterEntityIdentity(entityType, entityId);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void requireMetric(String metric) {
        if (!"like".equals(metric) && !"fav".equals(metric)) {
            throw new IllegalArgumentException("metric must be like or fav");
        }
    }

    private record DiscoveryResult(boolean complete, List<CounterEntityIdentity> candidates) {}
}
