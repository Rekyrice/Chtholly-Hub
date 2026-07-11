package com.chtholly.seed.contentpack.model;

/**
 * Stable mapping between a content-pack seed key and a persisted entity identity.
 */
public record SeedContentIdentity(
        String namespace,
        String entityType,
        String seedKey,
        long entityId,
        String packVersion,
        String contentHash,
        String metadataJson) {
}
