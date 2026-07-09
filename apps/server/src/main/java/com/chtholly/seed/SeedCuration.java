package com.chtholly.seed;

import java.time.Instant;
import java.util.List;

/**
 * Latest weekly seed/community curation stored for Hub discovery.
 */
public record SeedCuration(
        List<CuratedPost> posts,
        String collectionNote,
        Instant curatedAt
) {
}
