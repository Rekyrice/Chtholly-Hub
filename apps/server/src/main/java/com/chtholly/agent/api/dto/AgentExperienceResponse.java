package com.chtholly.agent.api.dto;

import java.time.Instant;

/**
 * Public response item for Chtholly's recent experience stream.
 *
 * @param text       first-person observation text
 * @param valueScore value gate score from 0.0 to 1.0
 * @param createdAt  creation time
 * @param source     source pipeline name
 */
public record AgentExperienceResponse(
        String text,
        double valueScore,
        int importance,
        Instant createdAt,
        String source
) {
}
