package com.chtholly.counter.mapper;

import com.chtholly.counter.schema.CounterSchema;

import java.util.Objects;

/**
 * Identifies one durable like or favorite membership fact.
 *
 * @param entityType entity type
 * @param entityId entity ID
 * @param metric {@code like} or {@code fav}
 * @param userId acting user ID
 */
public record CounterReactionKey(String entityType, String entityId, String metric, long userId) {

    public CounterReactionKey {
        CounterSchema.requirePersistableIdentity(entityType, entityId);
        if (!"like".equals(metric) && !"fav".equals(metric)) {
            throw new IllegalArgumentException("Counter reaction metric must be like or fav");
        }
        if (userId <= 0L) {
            throw new IllegalArgumentException("Counter reaction user ID must be positive");
        }
        Objects.requireNonNull(metric, "metric");
    }
}
