package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.SeedAssetDefinition;
import com.chtholly.seed.contentpack.model.SeedSourceDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ContentPackLoaderTest {

    private final ContentPackLoader loader = new ContentPackLoader();

    @Test
    void loadsVersionedContentPackFromRealFiles() throws Exception {
        ContentPack pack = loader.load(fixtureRoot());

        assertEquals("content-v2", pack.manifest().version());
        assertEquals("night-coder", pack.accounts().getFirst().seedKey());
        assertTrue(pack.accounts().getFirst().voice().interests().contains("可观测性"));
        assertTrue(pack.accounts().getFirst().voice().knowledgeBoundaries()
                .contains("不推测未采集的运行时状态"));
        assertTrue(pack.posts().getFirst().markdown().contains("一次真实排障"));
        assertTrue(pack.posts().getFirst().brief().factAnchors().contains("JVM 指标"));
        assertTrue(pack.posts().getFirst().brief().mediaPlan().contains("告警曲线截图"));
        assertTrue(pack.posts().getFirst().brief().sources().contains("应用日志"));
        assertTrue(pack.assets().containsKey("avatar-night-coder"));
        assertEquals("official-doc", pack.sources().get("spring-observability").type());
        assertEquals(1, pack.comments().size());
        assertEquals(1, pack.follows().size());
        assertEquals(1, pack.reactions().size());
        assertEquals(1, pack.views().size());
    }

    @Test
    void rejectsMarkdownPathEscapingContentPackRoot(@TempDir Path tempDir) throws Exception {
        copyFixture(fixtureRoot(), tempDir);
        Path postsFile = tempDir.resolve("posts.yml");
        String posts = Files.readString(postsFile, StandardCharsets.UTF_8)
                .replace("markdown/real-debugging.md", "../outside.md");
        Files.writeString(postsFile, posts, StandardCharsets.UTF_8);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> loader.load(tempDir));

        assertTrue(exception.getMessage().contains("Content pack path escapes root: ../outside.md"));
    }

    @Test
    void loadedNestedCollectionsAreImmutable() throws Exception {
        ContentPack pack = loader.load(fixtureRoot());
        var account = pack.accounts().getFirst();
        var voice = account.voice();
        var post = pack.posts().getFirst();
        var brief = post.brief();

        assertThrows(UnsupportedOperationException.class, () -> pack.manifest().expectedCategories().put("OTHER", 1));
        assertThrows(UnsupportedOperationException.class, () -> account.tags().add("mutable"));
        assertThrows(UnsupportedOperationException.class, () -> voice.commonPhrases().add("mutable"));
        assertThrows(UnsupportedOperationException.class, () -> voice.interests().add("mutable"));
        assertThrows(UnsupportedOperationException.class, () -> voice.biases().add("mutable"));
        assertThrows(UnsupportedOperationException.class, () -> voice.knowledgeBoundaries().add("mutable"));
        assertThrows(UnsupportedOperationException.class, () -> voice.forbiddenExpressions().add("mutable"));
        assertThrows(UnsupportedOperationException.class, () -> post.tags().add("mutable"));
        assertThrows(UnsupportedOperationException.class, () -> post.inlineAssets().add("mutable"));
        assertThrows(UnsupportedOperationException.class, () -> brief.factAnchors().add("mutable"));
        assertThrows(UnsupportedOperationException.class, () -> brief.mediaPlan().add("mutable"));
        assertThrows(UnsupportedOperationException.class, () -> brief.sources().add("mutable"));
        assertThrows(UnsupportedOperationException.class, () -> pack.accounts().add(account));
        assertThrows(UnsupportedOperationException.class, () -> pack.assets().put("mutable", pack.assets().values().iterator().next()));
        assertThrows(UnsupportedOperationException.class, () -> pack.sources().put("mutable", pack.sources().values().iterator().next()));
        assertThrows(UnsupportedOperationException.class,
                () -> pack.sources().values().iterator().next().factAnchors().add("mutable"));
    }

    @Test
    void sourceDefinitionNormalizesNullFactAnchors() {
        SeedSourceDefinition source = new SeedSourceDefinition(
                "source", "official-doc", "title", "https://example.com", "author",
                Instant.parse("2026-07-01T00:00:00Z"), null, null, "verification");

        assertEquals(java.util.List.of(), source.factAnchors());
    }

    @Test
    void loadsLegacyContentV2WithoutSourcesFile(@TempDir Path tempDir) throws Exception {
        copyFixture(fixtureRoot(), tempDir);
        Files.delete(tempDir.resolve("sources.yml"));

        ContentPack pack = loader.load(tempDir);

        assertTrue(pack.sources().isEmpty());
    }

    @Test
    void contentV3RequiresSourcesFile(@TempDir Path tempDir) throws Exception {
        copyFixture(fixtureRoot(), tempDir);
        Files.writeString(tempDir.resolve("manifest.yml"),
                Files.readString(tempDir.resolve("manifest.yml")).replace("content-v2", "content-v3"));
        Files.delete(tempDir.resolve("sources.yml"));

        var exception = assertThrows(java.io.UncheckedIOException.class, () -> loader.load(tempDir));

        assertTrue(exception.getMessage().contains("sources.yml"));
    }

    @Test
    void rejectsDuplicateAssetKeys(@TempDir Path tempDir) throws Exception {
        copyFixture(fixtureRoot(), tempDir);
        Path assets = tempDir.resolve("assets.yml");
        String yaml = Files.readString(assets);
        Files.writeString(assets, yaml + System.lineSeparator() + yaml);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> loader.load(tempDir));

        assertTrue(exception.getMessage().contains("duplicate asset key: avatar-night-coder"));
    }

    @Test
    void rejectsDuplicateSourceKeys(@TempDir Path tempDir) throws Exception {
        copyFixture(fixtureRoot(), tempDir);
        Path sources = tempDir.resolve("sources.yml");
        String yaml = Files.readString(sources);
        Files.writeString(sources, yaml + System.lineSeparator() + yaml);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> loader.load(tempDir));

        assertTrue(exception.getMessage().contains("duplicate source key: spring-observability"));
    }

    @Test
    void preservesSourceDeclarationOrder(@TempDir Path tempDir) throws Exception {
        copyFixture(fixtureRoot(), tempDir);
        Path sources = tempDir.resolve("sources.yml");
        Files.writeString(sources, Files.readString(sources) + """

- key: second-source
  type: article
  title: Second source
  pageUrl: https://example.com/second
  author: Example
  fetchedAt: 2026-07-02T00:00:00Z
  factAnchors: [second]
  quote: null
  usageNote: comparison
""");

        ContentPack pack = loader.load(tempDir);

        assertEquals(java.util.List.of("spring-observability", "second-source"),
                new ArrayList<>(pack.sources().keySet()));
    }

    @Test
    void contentPackRejectsNullAssetAndSourceMapEntries() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot());
        var asset = loaded.assets().values().iterator().next();
        var source = loaded.sources().values().iterator().next();
        Map<String, SeedAssetDefinition> assetNullKey = new LinkedHashMap<>();
        assetNullKey.put(null, asset);
        Map<String, SeedAssetDefinition> assetNullValue = new LinkedHashMap<>();
        assetNullValue.put("asset", null);
        Map<String, SeedSourceDefinition> sourceNullKey = new LinkedHashMap<>();
        sourceNullKey.put(null, source);
        Map<String, SeedSourceDefinition> sourceNullValue = new LinkedHashMap<>();
        sourceNullValue.put("source", null);

        assertThrows(NullPointerException.class, () -> copyWithMaps(loaded, assetNullKey, loaded.sources()));
        assertThrows(NullPointerException.class, () -> copyWithMaps(loaded, assetNullValue, loaded.sources()));
        assertThrows(NullPointerException.class, () -> copyWithMaps(loaded, loaded.assets(), sourceNullKey));
        assertThrows(NullPointerException.class, () -> copyWithMaps(loaded, loaded.assets(), sourceNullValue));
    }

    private ContentPack copyWithMaps(
            ContentPack loaded,
            Map<String, SeedAssetDefinition> assets,
            Map<String, SeedSourceDefinition> sources) {
        return new ContentPack(loaded.root(), loaded.manifest(), loaded.accounts(), assets, sources, loaded.posts(),
                loaded.comments(), loaded.follows(), loaded.reactions(), loaded.views());
    }

    @ParameterizedTest
    @ValueSource(strings = {"manifest.yml", "accounts.yml", "assets.yml", "sources.yml", "posts.yml", "interactions.yml"})
    void rejectsNullYamlRootWithFileContext(String fileName, @TempDir Path tempDir) throws Exception {
        copyFixture(fixtureRoot(), tempDir);
        Files.writeString(tempDir.resolve(fileName), "null\n", StandardCharsets.UTF_8);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> loader.load(tempDir));

        assertTrue(exception.getMessage().contains(fileName));
    }

    @Test
    void rejectsMarkdownSymlinkEscapingContentPackRoot(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("pack");
        copyFixture(fixtureRoot(), root);
        Path outside = tempDir.resolve("outside.md");
        Files.writeString(outside, "outside", StandardCharsets.UTF_8);
        Path link = root.resolve("markdown/linked.md");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
        String posts = Files.readString(root.resolve("posts.yml"), StandardCharsets.UTF_8)
                .replace("markdown/real-debugging.md", "markdown/linked.md");
        Files.writeString(root.resolve("posts.yml"), posts, StandardCharsets.UTF_8);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> loader.load(root));

        assertTrue(exception.getMessage().contains("Content pack path escapes root: markdown/linked.md"));
    }

    private Path fixtureRoot() throws URISyntaxException {
        return Path.of(getClass().getResource("/content-pack/valid").toURI());
    }

    private void copyFixture(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }
}
