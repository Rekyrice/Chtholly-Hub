package com.chtholly.seed.contentpack.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fully loaded, immutable filesystem input boundary for seed content.
 */
public record ContentPack(
        Path root,
        ContentPackManifest manifest,
        List<SeedAccountDefinition> accounts,
        Map<String, SeedAssetDefinition> assets,
        Map<String, SeedSourceDefinition> sources,
        List<SeedPostDefinition> posts,
        List<SeedCommentDefinition> comments,
        List<SeedFollowDefinition> follows,
        List<SeedReactionDefinition> reactions,
        List<SeedViewDefinition> views) {

    /**
     * Creates an immutable content pack and protects it from mutable parser collections.
     */
    public ContentPack {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        assets = immutableOrderedMap(assets);
        sources = immutableOrderedMap(sources);
        posts = posts == null ? List.of() : List.copyOf(posts);
        comments = comments == null ? List.of() : List.copyOf(comments);
        follows = follows == null ? List.of() : List.copyOf(follows);
        reactions = reactions == null ? List.of() : List.copyOf(reactions);
        views = views == null ? List.of() : List.copyOf(views);
    }

    private static <K, V> Map<K, V> immutableOrderedMap(Map<K, V> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * Creates a legacy content pack without structured source cards.
     */
    public ContentPack(
            Path root,
            ContentPackManifest manifest,
            List<SeedAccountDefinition> accounts,
            Map<String, SeedAssetDefinition> assets,
            List<SeedPostDefinition> posts,
            List<SeedCommentDefinition> comments,
            List<SeedFollowDefinition> follows,
            List<SeedReactionDefinition> reactions,
            List<SeedViewDefinition> views) {
        this(root, manifest, accounts, assets, Map.of(), posts, comments, follows, reactions, views);
    }
}
