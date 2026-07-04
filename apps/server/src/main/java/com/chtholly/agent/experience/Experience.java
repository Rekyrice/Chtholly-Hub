package com.chtholly.agent.experience;

import java.time.Instant;

/**
 * A timestamped experience in Chtholly's living stream.
 *
 * @param text       first-person experience text
 * @param importance importance score from 1 to 10
 * @param source     source event name
 * @param createdAt  creation time
 */
public record Experience(
        String text,
        int importance,
        String source,
        Instant createdAt
) {

    public Experience(String text, int importance, String source) {
        this(text, importance, source, Instant.now());
    }
}
