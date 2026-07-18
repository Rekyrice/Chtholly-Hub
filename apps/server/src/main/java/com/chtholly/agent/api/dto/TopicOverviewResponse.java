package com.chtholly.agent.api.dto;

import com.chtholly.agent.content.TopicClusterState;

import java.time.Instant;
import java.util.List;

/**
 * Public response describing topic clusters and their refresh lifecycle.
 *
 * @param items topic summaries in the current snapshot
 * @param state externally visible snapshot state
 * @param lastAttemptAt timestamp of the latest refresh attempt
 * @param lastSuccessAt timestamp of the latest successful refresh
 * @param windowDays clustering lookback window in days
 * @param reason stable machine-readable state reason
 */
public record TopicOverviewResponse(
        List<TopicClusterResponse> items,
        TopicClusterState state,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        int windowDays,
        String reason
) {
}
