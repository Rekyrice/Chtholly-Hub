package com.chtholly.agent.api.dto;

/**
 * Weekly Chtholly experience summary.
 *
 * @param weekKey ISO week key
 * @param summary summary text
 */
public record AgentWeeklyExperienceResponse(String weekKey, String summary) {
}
