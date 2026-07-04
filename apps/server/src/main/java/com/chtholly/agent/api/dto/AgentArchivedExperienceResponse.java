package com.chtholly.agent.api.dto;

import java.time.Instant;

/**
 * Long-term memorable Chtholly experience.
 *
 * @param id         archive row id
 * @param text       experience text
 * @param importance importance score
 * @param source     source event name
 * @param createdAt  original creation time
 * @param archivedAt archive time
 */
public record AgentArchivedExperienceResponse(
        Long id,
        String text,
        int importance,
        String source,
        Instant createdAt,
        Instant archivedAt
) {
}
