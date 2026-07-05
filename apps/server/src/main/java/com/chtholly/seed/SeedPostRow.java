package com.chtholly.seed;

import java.time.Instant;

/**
 * Published post row inserted by seed scripts.
 */
public record SeedPostRow(
        long id,
        long creatorId,
        String title,
        String slug,
        String description,
        String contentUrl,
        String contentObjectKey,
        long contentSize,
        String contentSha256,
        String tagsJson,
        String imgUrlsJson,
        Instant createTime,
        Instant updateTime,
        Instant publishTime
) {
}
