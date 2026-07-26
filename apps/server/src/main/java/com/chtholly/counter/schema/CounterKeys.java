package com.chtholly.counter.schema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Redis Key 生成工具。
 */
public final class CounterKeys {
    private CounterKeys() {}

    public static String sdsKey(String entityType, String entityId) {
        return String.format("cnt:%s:%s:%s", CounterSchema.SCHEMA_ID, entityType, entityId); // 固定结构计数（SDS）键
    }

    // 分片键：bm:{metric}:{etype}:{eid}:{chunk}
    public static String bitmapKey(String metric, String entityType, String entityId, long chunk) {
        return String.format("bm:%s:%s:%s:%d", metric, entityType, entityId, chunk); // 在线成员读投影（分片）
    }

    /** Set of non-empty Bitmap shard keys for one reaction metric and entity. */
    public static String bitmapShardIndexKey(String metric, String entityType, String entityId) {
        return String.format("bmidx:%s:%s:%s", metric, entityType, entityId);
    }

    /** Expected non-sentinel member count used to detect a partial shard-index loss. */
    public static String bitmapShardIndexCountKey(String metric, String entityType, String entityId) {
        return String.format("bmidxcnt:%s:%s:%s", metric, entityType, entityId);
    }

    /** Marks a fully rebuilt MySQL-backed reaction projection for one entity. */
    public static String reactionProjectionCompleteKey(String entityType, String entityId) {
        return String.format("counter:reaction-projection:complete:%s:%s", entityType, entityId);
    }

    /** Temporary Bitmap shard used only during one fenced MySQL-driven rebuild. */
    public static String reactionProjectionStageBitmapKey(
            String token,
            String metric,
            String entityType,
            String entityId,
            long chunk) {
        return String.format(
                "counter:reaction-rebuild:stage:%s:%s:%s:%s:%d",
                token, metric, entityType, entityId, chunk);
    }

    // 聚合增量持久化桶（Hash）：agg:{schema}:{etype}:{eid}
    public static String aggKey(String entityType, String entityId) {
        return String.format("agg:%s:%s:%s", CounterSchema.SCHEMA_ID, entityType, entityId); // 刷写前的增量存储桶
    }

    /** 活跃聚合桶索引（Set）：flush 时 O(1) 枚举，避免 KEYS 阻塞 Redis。 */
    public static String aggIndexKey() {
        return String.format("agg:%s:__keys", CounterSchema.SCHEMA_ID);
    }

    /** Per-entity write fence used while MySQL facts are projected into Redis. */
    public static String factMaintenanceFenceKey(String entityType, String entityId) {
        return String.format("counter:fact-maintenance:%s:%s", entityType, entityId);
    }

    /** Shared entity lock for exact fact maintenance and MySQL-driven projection rebuilds. */
    public static String factMaintenanceLockKey(String entityType, String entityId) {
        return String.format("lock:counter-fact-maintenance:%s:%s", entityType, entityId);
    }

    /** Monotonic generation carried by reaction events across fact maintenance boundaries. */
    public static String factEpochKey(String entityType, String entityId) {
        return String.format("counter:fact-epoch:%s:%s", entityType, entityId);
    }

    /** Persistent dedupe fact for events that explicitly opt into idempotent aggregation. */
    public static String eventDedupeKey(String eventId) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(eventId.getBytes(StandardCharsets.UTF_8)));
            return "counter:dedupe:" + CounterSchema.SCHEMA_ID + ":" + digest;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
