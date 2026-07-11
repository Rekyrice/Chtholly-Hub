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
        return String.format("bm:%s:%s:%s:%d", metric, entityType, entityId, chunk); // 位图事实层（分片）
    }

    // 聚合增量持久化桶（Hash）：agg:{schema}:{etype}:{eid}
    public static String aggKey(String entityType, String entityId) {
        return String.format("agg:%s:%s:%s", CounterSchema.SCHEMA_ID, entityType, entityId); // 刷写前的增量存储桶
    }

    /** 活跃聚合桶索引（Set）：flush 时 O(1) 枚举，避免 KEYS 阻塞 Redis。 */
    public static String aggIndexKey() {
        return String.format("agg:%s:__keys", CounterSchema.SCHEMA_ID);
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
