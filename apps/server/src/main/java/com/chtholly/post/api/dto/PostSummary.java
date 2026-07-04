package com.chtholly.post.api.dto;

import java.time.Instant;

/**
 * Minimal post summary used by background cognitive jobs.
 *
 * @param id          Post ID.
 * @param title       Post title.
 * @param description Short description.
 * @param publishTime Publish time.
 */
public record PostSummary(
        Long id,
        String title,
        String description,
        Instant publishTime
) {
}
