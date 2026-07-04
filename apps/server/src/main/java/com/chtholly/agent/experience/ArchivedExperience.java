package com.chtholly.agent.experience;

import java.time.Instant;

/**
 * A long-term archived experience that should not be forgotten.
 *
 * @param id         archive row id
 * @param text       experience text
 * @param importance importance score
 * @param source     source event name
 * @param createdAt  original creation time
 * @param archivedAt archive time
 */
public record ArchivedExperience(
        Long id,
        String text,
        int importance,
        String source,
        Instant createdAt,
        Instant archivedAt
) {
}
