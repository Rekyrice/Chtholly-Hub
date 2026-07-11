package com.chtholly.seed.contentpack.model;

import java.nio.file.Path;
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
        assets = assets == null ? Map.of() : Map.copyOf(assets);
        posts = posts == null ? List.of() : List.copyOf(posts);
        comments = comments == null ? List.of() : List.copyOf(comments);
        follows = follows == null ? List.of() : List.copyOf(follows);
        reactions = reactions == null ? List.of() : List.copyOf(reactions);
        views = views == null ? List.of() : List.copyOf(views);
    }
}
