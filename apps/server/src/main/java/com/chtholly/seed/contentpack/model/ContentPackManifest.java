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
        Map<String, Integer> expectedCategories) {

    /**
     * Protects the manifest from parser-owned map mutations.
     */
    public ContentPackManifest {
        expectedCategories = Map.copyOf(expectedCategories);
    }
}
