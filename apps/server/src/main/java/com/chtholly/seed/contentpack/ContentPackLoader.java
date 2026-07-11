package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.ContentPackManifest;
import com.chtholly.seed.contentpack.model.SeedAccountDefinition;
import com.chtholly.seed.contentpack.model.SeedAssetDefinition;
import com.chtholly.seed.contentpack.model.SeedCommentDefinition;
import com.chtholly.seed.contentpack.model.SeedFollowDefinition;
import com.chtholly.seed.contentpack.model.SeedPostDefinition;
import com.chtholly.seed.contentpack.model.SeedReactionDefinition;
import com.chtholly.seed.contentpack.model.SeedViewDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the five YAML documents and referenced Markdown files of a seed content pack.
 */
public final class ContentPackLoader {

    private static final TypeReference<List<SeedAccountDefinition>> ACCOUNTS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<SeedAssetDefinition>> ASSETS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<SeedPostDefinition>> POSTS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    /**
     * Creates a loader with YAML and Java time module discovery enabled.
     */
    public ContentPackLoader() {
        this.objectMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    }

    /**
     * Loads a content pack rooted at the supplied directory.
     *
     * @param root directory containing the five required YAML files
     * @return immutable content-pack input with hydrated Markdown
     * @throws IllegalArgumentException when a referenced path escapes the pack root
     * @throws UncheckedIOException when a required file cannot be read
     */
    public ContentPack load(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        ContentPackManifest manifest = readYaml(normalizedRoot, "manifest.yml", ContentPackManifest.class);
        List<SeedAccountDefinition> accounts = readYaml(normalizedRoot, "accounts.yml", ACCOUNTS_TYPE);
        List<SeedAssetDefinition> assetDefinitions = readYaml(normalizedRoot, "assets.yml", ASSETS_TYPE);
        List<SeedPostDefinition> postDefinitions = readYaml(normalizedRoot, "posts.yml", POSTS_TYPE);
        Interactions interactions = readYaml(normalizedRoot, "interactions.yml", Interactions.class);

        Map<String, SeedAssetDefinition> assets = indexAssets(assetDefinitions);
        List<SeedPostDefinition> posts = postDefinitions.stream()
                .map(post -> withMarkdown(normalizedRoot, post))
                .toList();

        return new ContentPack(
                normalizedRoot,
                manifest,
                accounts,
                assets,
                posts,
                interactions.comments(),
                interactions.follows(),
                interactions.reactions(),
                interactions.views());
    }

    private SeedPostDefinition withMarkdown(Path root, SeedPostDefinition post) {
        String markdown = readRequiredText(resolveMarkdownInside(root, post.markdownFile()));
        return new SeedPostDefinition(
                post.seedKey(),
                post.legacySlug(),
                post.authorSeedKey(),
                post.title(),
                post.slug(),
                post.description(),
                post.category(),
                post.tags(),
                post.publishTime(),
                post.markdownFile(),
                post.coverAsset(),
                post.inlineAssets(),
                post.brief(),
                markdown);
    }

    private Map<String, SeedAssetDefinition> indexAssets(List<SeedAssetDefinition> definitions) {
        Map<String, SeedAssetDefinition> assets = new LinkedHashMap<>();
        if (definitions != null) {
            definitions.forEach(asset -> assets.put(asset.key(), asset));
        }
        return assets;
    }

    private <T> T readYaml(Path root, String relative, Class<T> type) {
        Path path = resolveInside(root, relative);
        ensureReadable(path);
        try {
            return requireYamlRoot(objectMapper.readValue(path.toFile(), type), path);
        } catch (IOException exception) {
            throw unreadable(path, exception);
        }
    }

    private <T> T readYaml(Path root, String relative, TypeReference<T> type) {
        Path path = resolveInside(root, relative);
        ensureReadable(path);
        try {
            return requireYamlRoot(objectMapper.readValue(path.toFile(), type), path);
        } catch (IOException exception) {
            throw unreadable(path, exception);
        }
    }

    private <T> T requireYamlRoot(T value, Path path) {
        if (value == null) {
            throw new IllegalStateException("Content pack YAML root must not be null: " + path);
        }
        return value;
    }

    private String readRequiredText(Path path) {
        ensureReadable(path);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw unreadable(path, exception);
        }
    }

    private void ensureReadable(Path path) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw unreadable(path, new IOException("Required file is missing or unreadable"));
        }
    }

    private UncheckedIOException unreadable(Path path, IOException cause) {
        return new UncheckedIOException("Required content pack file is missing or unreadable: " + path, cause);
    }

    private Path resolveMarkdownInside(Path root, String relative) {
        Path resolved = resolveInside(root, relative);
        ensureReadable(resolved);
        try {
            Path realRoot = root.toRealPath();
            Path realResolved = resolved.toRealPath();
            if (!realResolved.startsWith(realRoot)) {
                throw new IllegalArgumentException("Content pack path escapes root: " + relative);
            }
            return realResolved;
        } catch (IOException exception) {
            throw unreadable(resolved, exception);
        }
    }

    private static Path resolveInside(Path root, String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Content pack path escapes root: " + relative);
        }
        return resolved;
    }

    private record Interactions(
            List<SeedCommentDefinition> comments,
            List<SeedFollowDefinition> follows,
            List<SeedReactionDefinition> reactions,
            List<SeedViewDefinition> views) {
    }
}
