package com.chtholly.agent.api.dto;

import java.util.List;

/**
 * Three-layer experience timeline for the Chtholly room.
 *
 * @param recent          raw recent experiences from the last short-term stream
 * @param weeklySummaries consolidated medium-term summaries
 * @param archived        long-term memorable archived moments
 */
public record AgentExperienceTimelineResponse(
        List<AgentExperienceResponse> recent,
        List<AgentWeeklyExperienceResponse> weeklySummaries,
        List<AgentArchivedExperienceResponse> archived
) {
}
