package com.chtholly.agent.cognitive;

import java.time.Instant;

/**
 * A first-person observation produced by Chtholly's cognitive loop.
 *
 * @param text       First-person thought text.
 * @param valueScore Importance/usefulness score from 0.0 to 1.0.
 * @param createdAt  Creation time.
 * @param source     Source pipeline.
 */
public record Observation(
        String text,
        double valueScore,
        Instant createdAt,
        String source
) {
}
