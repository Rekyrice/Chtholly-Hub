package com.chtholly.agent.experience;

/**
 * A consolidated memory summary for one ISO week.
 *
 * @param weekKey ISO week key such as 2026-W27
 * @param summary summarized experience text
 */
public record WeeklyExperienceSummary(String weekKey, String summary) {
}
