package com.chtholly.agent.learning;

import java.time.Instant;

/**
 * A learned behavior rule extracted from completed agent conversations.
 *
 * @param id              Stable SHA-256 prefix for deduplication.
 * @param text            Behavior rule text.
 * @param createdAt       Creation timestamp.
 * @param lastUsedAt      Last prompt injection timestamp.
 * @param useCount        Number of prompt injections.
 * @param confidenceScore Confidence score from 0.0 to 1.0.
 * @param state           Lifecycle state.
 */
public record Insight(
        String id,
        String text,
        Instant createdAt,
        Instant lastUsedAt,
        int useCount,
        double confidenceScore,
        InsightState state
) {
    public enum InsightState {
        ACTIVE,
        STALE,
        ARCHIVED
    }
}
