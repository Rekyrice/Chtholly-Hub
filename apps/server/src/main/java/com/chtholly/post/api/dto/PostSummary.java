package com.chtholly.post.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Minimal post summary used by background cognitive jobs.
 *
 * @param id          Post ID.
 * @param title       Post title.
 * @param description Short description.
 * @param publishTime Publish time.
 * @param tags        Post tags (may be empty).
 */
public record PostSummary(
        Long id,
        String title,
        String description,
        Instant publishTime,
        List<String> tags
) {
    public PostSummary(Long id, String title, String description, Instant publishTime) {
        this(id, title, description, publishTime, List.of());
    }
}
