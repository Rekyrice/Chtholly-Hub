package com.chtholly.seed.contentpack.model;

import java.time.Instant;
import java.util.List;

/**
 * Immutable article input, including Markdown hydrated by the content-pack loader.
 */
public record SeedPostDefinition(
        String seedKey,
        String legacySlug,
        String authorSeedKey,
        String title,
        String slug,
        String description,
        String category,
        List<String> tags,
        Instant publishTime,
        String markdownFile,
        String coverAsset,
        List<String> inlineAssets,
        ArticleBrief brief,
        String markdown) {

    /**
     * Protects article metadata from parser-owned list mutations.
     */
    public SeedPostDefinition {
        tags = List.copyOf(tags);
        inlineAssets = List.copyOf(inlineAssets);
    }

    /**
     * Six approved dimensions that guide article authoring.
     */
    public record ArticleBrief(
            List<String> factAnchors,
            String voice,
            String position,
            String format,
            List<String> mediaPlan,
            List<String> sources) {

        /**
         * Protects brief dimensions from parser-owned list mutations.
         */
        public ArticleBrief {
            factAnchors = List.copyOf(factAnchors);
            mediaPlan = List.copyOf(mediaPlan);
            sources = List.copyOf(sources);
        }
    }
}
