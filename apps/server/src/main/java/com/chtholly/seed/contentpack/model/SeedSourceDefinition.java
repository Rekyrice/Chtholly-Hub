package com.chtholly.seed.contentpack.model;

import java.time.Instant;
import java.util.List;

/**
 * Immutable provenance card for facts referenced by seeded articles.
 */
public record SeedSourceDefinition(
        String key,
        String type,
        String title,
        String pageUrl,
        String author,
        Instant fetchedAt,
        List<String> factAnchors,
        String quote,
        String usageNote) {

    /**
     * Protects fact anchors from parser-owned collections.
     */
    public SeedSourceDefinition {
        factAnchors = factAnchors == null ? List.of() : List.copyOf(factAnchors);
    }
}
