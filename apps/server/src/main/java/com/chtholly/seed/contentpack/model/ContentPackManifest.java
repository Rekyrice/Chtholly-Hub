package com.chtholly.seed.contentpack.model;

import java.util.Map;

/**
 * Version and expected composition declared by a seed content pack.
 */
public record ContentPackManifest(
        String version,
        String namespace,
        String stage,
        int expectedAccounts,
        int expectedPosts,
        int expectedRetirements,
        Map<String, Integer> expectedCategories) {

    /**
     * Protects the manifest from parser-owned map mutations.
     */
    public ContentPackManifest {
        expectedCategories = Map.copyOf(expectedCategories);
    }

    /**
     * Creates a legacy manifest without retirement declarations.
     */
    public ContentPackManifest(
            String version,
            String namespace,
            String stage,
            int expectedAccounts,
            int expectedPosts,
            Map<String, Integer> expectedCategories) {
        this(version, namespace, stage, expectedAccounts, expectedPosts, 0, expectedCategories);
    }
}
