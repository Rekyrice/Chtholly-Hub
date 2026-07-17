package com.chtholly.agent.content;

import java.time.Instant;

/**
 * Redis-persisted metadata for the latest topic-cluster refresh run.
 *
 * @param state refresh lifecycle state
 * @param lastAttemptAt timestamp of the latest refresh attempt
 * @param lastSuccessAt timestamp of the latest successful refresh
 * @param reason stable machine-readable state reason
 */
public record TopicClusterRunStatus(
        TopicClusterState state,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        String reason
) {
}
