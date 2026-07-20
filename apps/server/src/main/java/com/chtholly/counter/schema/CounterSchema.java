package com.chtholly.counter.schema;

import java.util.Map;
import java.util.Set;

/**
 * 计数 Schema 映射及常量定义。
 * 阶段一采用 8 字节 Int64 固定偏移（SDS），后续可平滑替换为 5 字节实现。
 */
public final class CounterSchema {

    // 使用 v1 Schema：下标约定（可扩展）
    // 0: read（预留）
    // 1: like
    // 2: fav
    // 3: comment（预留）
    // 4: repost（预留）
    public static final String SCHEMA_ID = "v1";
    public static final int FIELD_SIZE = 4; // 改为 4 字节 Int32 存储
    public static final int SCHEMA_LEN = 5; // 预留 5 个指标位

    public static final int IDX_VIEW = 0;
    public static final int IDX_LIKE = 1;
    public static final int IDX_FAV = 2;
    public static final int MAX_ENTITY_TYPE_LENGTH = 32;
    public static final int MAX_ENTITY_ID_LENGTH = 64;

    public static final Map<String, Integer> NAME_TO_IDX = Map.of(
            "view", IDX_VIEW,
            "like", IDX_LIKE,
            "fav", IDX_FAV
    );

    public static final Set<String> SUPPORTED_METRICS = NAME_TO_IDX.keySet(); // 对外可请求的指标集合

    /** Enforces the identity bounds shared by Redis mutation and MySQL recovery. */
    public static void requirePersistableIdentity(String entityType, String entityId) {
        if (entityType == null || entityType.isBlank() || entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("Counter entity identity is required");
        }
        if (entityType.length() > MAX_ENTITY_TYPE_LENGTH || entityId.length() > MAX_ENTITY_ID_LENGTH) {
            throw new IllegalArgumentException("Counter entity identity exceeds persistence limits");
        }
        if (!isRedisKeySafe(entityType) || !isRedisKeySafe(entityId)) {
            throw new IllegalArgumentException("Counter entity identity contains unsupported characters");
        }
    }

    private static boolean isRedisKeySafe(String value) {
        return value.chars().allMatch(character ->
                (character >= 'a' && character <= 'z')
                        || (character >= 'A' && character <= 'Z')
                        || (character >= '0' && character <= '9')
                        || character == '.' || character == '_' || character == '-');
    }

    private CounterSchema() {}
}
