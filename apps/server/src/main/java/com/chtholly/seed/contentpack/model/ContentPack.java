package com.chtholly.seed.contentpack.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        assets = orderedImmutableMap(assets);
        sources = orderedImmutableMap(sources);
        posts = posts == null ? List.of() : List.copyOf(posts);
        comments = comments == null ? List.of() : List.copyOf(comments);
        follows = follows == null ? List.of() : List.copyOf(follows);
        reactions = reactions == null ? List.of() : List.copyOf(reactions);
        views = views == null ? List.of() : List.copyOf(views);
    }

    private static <K, V> Map<K, V> orderedImmutableMap(Map<K, V> values) {
        Objects.requireNonNull(values, "map");
        if (values.isEmpty()) {
            return Map.of();
        }
        Map<K, V> ordered = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : values.entrySet()) {
            ordered.put(
                    Objects.requireNonNull(entry.getKey(), "map key"),
                    Objects.requireNonNull(entry.getValue(), "map value"));
        }
        return Collections.unmodifiableMap(ordered);
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
