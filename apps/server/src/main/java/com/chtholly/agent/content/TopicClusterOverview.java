package com.chtholly.agent.content;

import java.time.Instant;
import java.util.List;

/**
 * Domain view of the current topic-cluster snapshot and refresh lifecycle.
 *
 * @param items topic clusters in the current snapshot
 * @param state externally visible snapshot state
 * @param lastAttemptAt timestamp of the latest refresh attempt
 * @param lastSuccessAt timestamp of the latest successful refresh
 * @param windowDays clustering lookback window in days
 * @param reason stable machine-readable state reason
 */
public record TopicClusterOverview(
        List<TopicCluster> items,
        TopicClusterState state,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        int windowDays,
        String reason
) {
}
