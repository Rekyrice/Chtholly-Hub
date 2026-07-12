package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.ContentPackManifest;
import com.chtholly.seed.contentpack.model.SeedAccountDefinition;
import com.chtholly.seed.contentpack.model.SeedAssetDefinition;
import com.chtholly.seed.contentpack.model.SeedCommentDefinition;
import com.chtholly.seed.contentpack.model.SeedPostDefinition;
import com.chtholly.seed.contentpack.model.SeedSourceDefinition;
import com.chtholly.seed.contentpack.model.SeedReactionDefinition;
import com.chtholly.seed.contentpack.model.SeedViewDefinition;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentPackValidatorTest {

    private final ContentPackLoader loader = new ContentPackLoader();
    private final ContentPackValidator validator = new ContentPackValidator();

    @Test
    void reportsAllStructuralErrorsInDeterministicOrder() throws Exception {
        ContentPack pack = loader.load(fixtureRoot("invalid/structure"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(pack));

        String message = exception.getMessage();
        assertContainsInOrder(message,
                "expected account count: 3, actual: 2",
                "expected post count: 3, actual: 2",
                "duplicate account handle: repeated_handle",
                "duplicate post seedKey: post-01",
                "duplicate post slug: repeated-slug",
                "missing post author: post-01 -> absent-author",
                "missing cover asset: post-01 -> missing-cover",
                "missing inline asset: post-01 -> missing-inline",
                "blank Markdown: post-01",
                "invalid reaction type: reaction-01 -> CLAP",
                "self-follow: follow-01");
    }

    @Test
    void rejectsReferencesAndTimestampsThatCannotFormAValidTimeline() throws Exception {
        ContentPack pack = loader.load(fixtureRoot("invalid/references"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(pack));

        String message = exception.getMessage();
        assertTrue(message.contains("invalid account handle: bad-handle"));
        assertTrue(message.contains("invalid description length: post-01"));
        assertTrue(message.contains("missing comment post: comment-01 -> absent-post"));
        assertTrue(message.contains("missing view post: view-01 -> absent-post"));
        assertTrue(message.contains("negative view baseline: view-02 -> -1"));
        assertTrue(message.contains("interaction does not follow publication: comment-02"));
        assertTrue(message.contains("missing timestamp: post post-02"));
    }

    @Test
    void rejectsADeclaredPathOutsideThePackRoot() throws Exception {
        ContentPack pack = loader.load(fixtureRoot("valid"));
        SeedPostDefinition original = pack.posts().getFirst();
        SeedPostDefinition escaping = new SeedPostDefinition(
                original.seedKey(), original.legacySlug(), original.authorSeedKey(), original.title(), original.slug(),
                original.description(), original.category(), original.tags(), original.publishTime(), "../outside.md",
                original.coverAsset(), original.inlineAssets(), original.brief(), original.markdown());
        ContentPack modified = new ContentPack(pack.root(), pack.manifest(), pack.accounts(), pack.assets(),
                java.util.List.of(escaping), pack.comments(), pack.follows(), pack.reactions(), pack.views());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(modified));

        assertTrue(exception.getMessage().contains("path escapes root: post-real-debugging -> ../outside.md"));
    }

    @Test
    void rejectsCommentTimestampEqualToPublicationTime() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot("invalid/references"));
        SeedPostDefinition post = loaded.posts().getFirst();
        SeedCommentDefinition original = loaded.comments().get(1);
        SeedCommentDefinition simultaneous = new SeedCommentDefinition(
                original.seedKey(), original.legacyOrdinal(), original.postSeedKey(), original.authorSeedKey(),
                original.parentSeedKey(), original.content(), post.publishTime());
        ContentPack pack = new ContentPack(loaded.root(), loaded.manifest(), loaded.accounts(), loaded.assets(),
                loaded.posts(), java.util.List.of(loaded.comments().getFirst(), simultaneous), loaded.follows(),
                loaded.reactions(), loaded.views());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(pack));

        assertTrue(exception.getMessage().contains("interaction does not follow publication: comment-02"));
    }

    @Test
    void collectsMissingCategoryTogetherWithOtherPostErrors() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot("invalid/references"));
        SeedPostDefinition original = loaded.posts().getFirst();
        SeedPostDefinition missingCategory = new SeedPostDefinition(
                original.seedKey(), original.legacySlug(), original.authorSeedKey(), original.title(), original.slug(),
                original.description(), null, original.tags(), original.publishTime(), original.markdownFile(),
                original.coverAsset(), original.inlineAssets(), original.brief(), original.markdown());
        ContentPack pack = new ContentPack(loaded.root(), loaded.manifest(), loaded.accounts(), loaded.assets(),
                java.util.List.of(missingCategory, loaded.posts().get(1)), loaded.comments(), loaded.follows(),
                loaded.reactions(), loaded.views());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(pack));

        assertTrue(exception.getMessage().contains("missing post category: post-01"));
        assertTrue(exception.getMessage().contains("invalid description length: post-01"));
    }

    @Test
    void rejectsHandleAndSlugVariantsThatOnlyDifferByCase() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot("invalid/structure"));
        SeedAccountDefinition secondAccount = loaded.accounts().get(1);
        SeedAccountDefinition caseVariantAccount = new SeedAccountDefinition(
                secondAccount.seedKey(), secondAccount.legacyHandle(), secondAccount.nickname(), "REPEATED_HANDLE",
                secondAccount.bio(), secondAccount.avatarAsset(), secondAccount.gender(), secondAccount.birthday(),
                secondAccount.school(), secondAccount.tags(), secondAccount.voice());
        SeedPostDefinition secondPost = loaded.posts().get(1);
        SeedPostDefinition caseVariantPost = new SeedPostDefinition(
                secondPost.seedKey(), secondPost.legacySlug(), secondPost.authorSeedKey(), secondPost.title(),
                "REPEATED-SLUG", secondPost.description(), secondPost.category(), secondPost.tags(),
                secondPost.publishTime(), secondPost.markdownFile(), secondPost.coverAsset(), secondPost.inlineAssets(),
                secondPost.brief(), secondPost.markdown());
        ContentPack pack = new ContentPack(loaded.root(), loaded.manifest(),
                java.util.List.of(loaded.accounts().getFirst(), caseVariantAccount), loaded.assets(),
                java.util.List.of(loaded.posts().getFirst(), caseVariantPost), loaded.comments(), loaded.follows(),
                loaded.reactions(), loaded.views());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(pack));

        assertTrue(exception.getMessage().contains("duplicate account handle: REPEATED_HANDLE"));
        assertTrue(exception.getMessage().contains("duplicate post slug: REPEATED-SLUG"));
    }

    @Test
    void acceptsACompleteStructurallyValidPack() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot("valid"));
        var accounts = loaded.accounts().stream()
                .map(account -> new SeedAccountDefinition(
                        account.seedKey(), account.legacyHandle(), account.nickname(), account.handle().replace('-', '_'),
                        account.bio(), account.avatarAsset(), account.gender(), account.birthday(), account.school(),
                        account.tags(), account.voice()))
                .toList();
        var reactions = loaded.reactions().stream()
                .map(reaction -> new SeedReactionDefinition(
                        reaction.seedKey(), reaction.postSeedKey(), reaction.accountSeedKey(), reaction.type().toLowerCase()))
                .toList();
        ContentPack pack = new ContentPack(loaded.root(),
                new ContentPackManifest(loaded.manifest().version(), loaded.manifest().namespace(), "review",
                        loaded.manifest().expectedAccounts(), loaded.manifest().expectedPosts(),
                        loaded.manifest().expectedCategories()),
                accounts, loaded.assets(), loaded.posts(), loaded.comments(), loaded.follows(), reactions, loaded.views());

        ContentPackValidator.ValidationResult result = validator.validate(pack);

        assertEquals(java.util.List.of(), result.warnings());
    }

    @Test
    void rejectsCrossPostParentCycleAndReplyBeforeParent() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot("valid"));
        SeedCommentDefinition first = new SeedCommentDefinition(
                "first", null, "post-a", loaded.accounts().getFirst().seedKey(), "second", "one",
                java.time.Instant.parse("2026-06-03T00:00:00Z"));
        SeedCommentDefinition second = new SeedCommentDefinition(
                "second", null, "post-b", loaded.accounts().getFirst().seedKey(), "first", "two",
                java.time.Instant.parse("2026-06-04T00:00:00Z"));
        ContentPack pack = new ContentPack(loaded.root(), loaded.manifest(), loaded.accounts(), loaded.assets(),
                loaded.posts(), java.util.List.of(first, second), loaded.follows(), loaded.reactions(), loaded.views());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> validator.validate(pack));

        assertTrue(exception.getMessage().contains("comment parent post mismatch: first -> second"));
        assertTrue(exception.getMessage().contains("comment parent cycle:"));
        assertTrue(exception.getMessage().contains("comment precedes parent: first -> second"));
    }

    @Test
    void rejectsViewBaselineAboveInt32StorageRange() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot("valid"));
        SeedViewDefinition overflow = new SeedViewDefinition(
                "view-overflow", loaded.posts().getFirst().seedKey(), (long) Integer.MAX_VALUE + 1L);
        ContentPack pack = new ContentPack(loaded.root(), loaded.manifest(), loaded.accounts(), loaded.assets(),
                loaded.posts(), loaded.comments(), loaded.follows(), loaded.reactions(), java.util.List.of(overflow));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> validator.validate(pack));

        assertTrue(exception.getMessage().contains("view baseline exceeds Int32: view-overflow"));
    }

    @Test
    void contentV3RequiresKnownStructuredSourceForEveryPost() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot("valid"));
        SeedPostDefinition original = loaded.posts().getFirst();
        SeedPostDefinition post = withSources(original, List.of("missing-source"), original.coverAsset());
        ContentPack pack = contentV3(loaded, Map.of(), Map.of(original.coverAsset(), loaded.assets().get(original.coverAsset())), post);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> validator.validate(pack));

        assertTrue(exception.getMessage().contains("missing post source: " + post.seedKey() + " -> missing-source"));
    }

    @Test
    void contentV3RequiresSourceOnEveryPost() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot("valid"));
        SeedPostDefinition post = withSources(loaded.posts().getFirst(), List.of(), null);
        ContentPack pack = contentV3(loaded, Map.of(), loaded.assets(), post);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> validator.validate(pack));

        assertTrue(exception.getMessage().contains("missing post source: " + post.seedKey()));
        assertTrue(!exception.getMessage().contains("missing cover asset"));
    }

    @Test
    void webAssetsRequireCompleteProvenanceMetadata() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot("valid"));
        SeedAssetDefinition original = loaded.assets().values().iterator().next();
        SeedAssetDefinition webAsset = new SeedAssetDefinition(
                original.key(), "public-web", "https://cdn.example/image.png", null, null, null,
                original.sourceFile(), original.file(), original.objectKey(), original.sha256(), original.contentType(),
                original.width(), original.height(), original.usage());
        SeedSourceDefinition source = source("spring-observability");
        SeedPostDefinition post = withSources(loaded.posts().getFirst(), List.of(source.key()), null);
        ContentPack pack = contentV3(loaded, Map.of(source.key(), source), Map.of(webAsset.key(), webAsset), post);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> validator.validate(pack));

        assertTrue(exception.getMessage().contains("missing asset source page: " + webAsset.key()));
        assertTrue(exception.getMessage().contains("missing asset fetched timestamp: " + webAsset.key()));
        assertTrue(exception.getMessage().contains("missing asset usage note: " + webAsset.key()));
    }

    @Test
    void rejectsAiGeneratedAssetMarkersInEveryProvenanceFieldForOldVersions() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot("valid"));
        SeedAssetDefinition original = loaded.assets().values().iterator().next();
        SeedAssetDefinition forbidden = new SeedAssetDefinition(
                original.key(), "Generated: illustration", "https://example/openai-imagegen/file.png",
                "https://GocrazyAI.example/page", null, null, original.sourceFile(), original.file(),
                original.objectKey(), original.sha256(), original.contentType(), original.width(), original.height(), original.usage());
        ContentPack pack = new ContentPack(loaded.root(), loaded.manifest(), loaded.accounts(),
                Map.of(forbidden.key(), forbidden), loaded.posts(), loaded.comments(), loaded.follows(),
                loaded.reactions(), loaded.views());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> validator.validate(pack));

        assertTrue(exception.getMessage().contains("AI-generated asset forbidden: " + forbidden.key()));
    }

    @Test
    void contentV2DoesNotRequireStrictWebAssetProvenance() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot("valid"));
        SeedAssetDefinition original = loaded.assets().values().iterator().next();
        SeedAssetDefinition legacyWebAsset = new SeedAssetDefinition(
                original.key(), "public-web", "https://cdn.example/image.png", null, null, null,
                original.sourceFile(), original.file(), original.objectKey(), original.sha256(), original.contentType(),
                original.width(), original.height(), original.usage());
        var accounts = loaded.accounts().stream()
                .map(account -> new SeedAccountDefinition(
                        account.seedKey(), account.legacyHandle(), account.nickname(), account.handle().replace('-', '_'),
                        account.bio(), account.avatarAsset(), account.gender(), account.birthday(), account.school(),
                        account.tags(), account.voice()))
                .toList();
        var reactions = loaded.reactions().stream()
                .map(reaction -> new SeedReactionDefinition(
                        reaction.seedKey(), reaction.postSeedKey(), reaction.accountSeedKey(), reaction.type().toLowerCase()))
                .toList();
        ContentPack pack = new ContentPack(loaded.root(),
                new ContentPackManifest(loaded.manifest().version(), loaded.manifest().namespace(), "review",
                        loaded.manifest().expectedAccounts(), loaded.manifest().expectedPosts(),
                        loaded.manifest().expectedCategories()),
                accounts, Map.of(legacyWebAsset.key(), legacyWebAsset), loaded.posts(), loaded.comments(),
                loaded.follows(), reactions, loaded.views());

        ContentPackValidator.ValidationResult result = validator.validate(pack);

        assertEquals(List.of(), result.warnings());
    }

    @Test
    void contentV3ReportsEveryInvalidSourceCardFieldInDeclarationOrder() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot("valid"));
        Map<String, SeedSourceDefinition> sources = new LinkedHashMap<>();
        sources.put("blank-key", new SeedSourceDefinition(" ", "official-doc", "Title", "https://example.com/a",
                "Author", Instant.parse("2026-07-01T00:00:00Z"), List.of("fact"), null, "usage"));
        sources.put("blank-type", new SeedSourceDefinition("blank-type", " ", "Title", "https://example.com/b",
                "Author", Instant.parse("2026-07-01T00:00:00Z"), List.of("fact"), null, "usage"));
        sources.put("blank-title", new SeedSourceDefinition("blank-title", "article", null, "https://example.com/c",
                "Author", Instant.parse("2026-07-01T00:00:00Z"), List.of("fact"), null, "usage"));
        sources.put("blank-page", new SeedSourceDefinition("blank-page", "article", "Title", " ", "Author",
                Instant.parse("2026-07-01T00:00:00Z"), List.of("fact"), null, "usage"));
        sources.put("bad-page", new SeedSourceDefinition("bad-page", "article", "Title", "https:///missing-host",
                "Author", Instant.parse("2026-07-01T00:00:00Z"), List.of("fact"), null, "usage"));
        sources.put("no-fetch", new SeedSourceDefinition("no-fetch", "article", "Title", "https://example.com/f",
                "Author", null, List.of("fact"), null, "usage"));
        sources.put("no-usage", new SeedSourceDefinition("no-usage", "article", "Title", "https://example.com/g",
                "Author", Instant.parse("2026-07-01T00:00:00Z"), List.of("fact"), null, null));
        sources.put("no-facts", new SeedSourceDefinition("no-facts", "article", "Title", "https://example.com/h",
                "Author", Instant.parse("2026-07-01T00:00:00Z"), List.of(), null, "usage"));
        SeedPostDefinition post = withSources(loaded.posts().getFirst(), List.of("blank-type"), null);
        ContentPack pack = contentV3(loaded, sources, loaded.assets(), post);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> validator.validate(pack));

        assertContainsInOrder(exception.getMessage(),
                "blank source key",
                "blank source type: blank-type",
                "blank source title: blank-title",
                "missing source page URL: blank-page",
                "invalid source page URL: bad-page",
                "missing source fetched timestamp: no-fetch",
                "missing source usage note: no-usage",
                "missing source fact anchors: no-facts");
    }

    @Test
    void rejectsObfuscatedAiMarkersAcrossAllTextualProvenanceFieldsInStableOrder() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot("valid"));
        SeedAssetDefinition original = loaded.assets().values().iterator().next();
        Map<String, SeedAssetDefinition> assets = new LinkedHashMap<>();
        assets.put("source-marker", assetWithProvenance(original, "source-marker", " openai_imagegen ", null, null, null, null));
        assets.put("url-marker", assetWithProvenance(original, "url-marker", "local", "https://example.com/openai imagegen", null, null, null));
        assets.put("page-marker", assetWithProvenance(original, "page-marker", "local", null, "https://example.com/generated : art", null, null));
        assets.put("file-marker", assetWithProvenance(original, "file-marker", "local", null, null, "GoCrazy-AI/source.png", null));
        assets.put("usage-marker", assetWithProvenance(original, "usage-marker", "local", null, null, null, "generated : illustration"));
        assets.put("domain-marker", assetWithProvenance(original, "domain-marker", "local", null, "https://gocrazy.ai/art", null, null));
        assets.put("dot-marker", assetWithProvenance(original, "dot-marker", "local", null, null, null, "openai.imagegen"));
        assets.put("mixed-marker", assetWithProvenance(original, "mixed-marker", "local", null, null, "generated/:/art.png", null));
        assets.put("legal-url", assetWithProvenance(original, "legal-url", "local",
                "https://images.example.com/art-v2.png", "https://example.com/license", null, "editorial image"));
        ContentPack pack = new ContentPack(loaded.root(), loaded.manifest(), loaded.accounts(), assets,
                loaded.posts(), loaded.comments(), loaded.follows(), loaded.reactions(), loaded.views());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> validator.validate(pack));

        assertContainsInOrder(exception.getMessage(),
                "AI-generated asset forbidden: source-marker",
                "AI-generated asset forbidden: url-marker",
                "AI-generated asset forbidden: page-marker",
                "AI-generated asset forbidden: file-marker",
                "AI-generated asset forbidden: usage-marker",
                "AI-generated asset forbidden: domain-marker",
                "AI-generated asset forbidden: dot-marker",
                "AI-generated asset forbidden: mixed-marker");
        assertTrue(!exception.getMessage().contains("AI-generated asset forbidden: legal-url"));
    }

    @Test
    void contentV3RejectsHttpAssetUrlWithoutHostAfterTrimming() throws Exception {
        ContentPack loaded = loader.load(fixtureRoot("valid"));
        SeedAssetDefinition original = loaded.assets().values().iterator().next();
        SeedAssetDefinition invalid = new SeedAssetDefinition(
                original.key(), "local", "  https:///missing-host  ", "https://example.com/page",
                Instant.parse("2026-07-01T00:00:00Z"), "illustrates fact", original.sourceFile(), original.file(),
                original.objectKey(), original.sha256(), original.contentType(), original.width(), original.height(),
                original.usage());
        SeedSourceDefinition source = source("spring-observability");
        SeedPostDefinition post = withSources(loaded.posts().getFirst(), List.of(source.key()), null);
        ContentPack pack = contentV3(loaded, Map.of(source.key(), source), Map.of(invalid.key(), invalid), post);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> validator.validate(pack));

        assertTrue(exception.getMessage().contains("invalid asset source URL: " + invalid.key()));
    }

    private SeedAssetDefinition assetWithProvenance(SeedAssetDefinition original, String key, String source,
                                                     String sourceUrl, String sourcePageUrl, String sourceFile,
                                                     String usageNote) {
        return new SeedAssetDefinition(key, source, sourceUrl, sourcePageUrl, original.fetchedAt(), usageNote,
                sourceFile, original.file(), original.objectKey(), original.sha256(), original.contentType(),
                original.width(), original.height(), original.usage());
    }

    private ContentPack contentV3(ContentPack loaded, Map<String, SeedSourceDefinition> sources,
                                  Map<String, SeedAssetDefinition> assets, SeedPostDefinition post) {
        return new ContentPack(loaded.root(),
                new ContentPackManifest("content-v3", loaded.manifest().namespace(), "review",
                        loaded.accounts().size(), 1, loaded.manifest().expectedCategories()),
                loaded.accounts(), assets, sources, List.of(post), loaded.comments(), loaded.follows(),
                loaded.reactions(), loaded.views());
    }

    private SeedPostDefinition withSources(SeedPostDefinition original, List<String> sources, String coverAsset) {
        var brief = new SeedPostDefinition.ArticleBrief(original.brief().factAnchors(), original.brief().voice(),
                original.brief().position(), original.brief().format(), original.brief().mediaPlan(), sources);
        return new SeedPostDefinition(original.seedKey(), original.legacySlug(), original.authorSeedKey(),
                original.title(), original.slug(), original.description(), original.category(), original.tags(),
                original.publishTime(), original.markdownFile(), coverAsset, original.inlineAssets(), brief,
                original.markdown());
    }

    private SeedSourceDefinition source(String key) {
        return new SeedSourceDefinition(key, "official-doc", "Spring observability", "  https://spring.io/docs  ",
                "Spring", Instant.parse("2026-07-01T00:00:00Z"), List.of("metrics"), null, "fact checking");
    }

    private Path fixtureRoot(String name) throws URISyntaxException {
        return Path.of(getClass().getResource("/content-pack/" + name).toURI());
    }

    private void assertContainsInOrder(String text, String... values) {
        int position = -1;
        for (String value : values) {
            int next = text.indexOf(value);
            assertTrue(next > position, () -> "Expected ordered value '" + value + "' in:\n" + text);
            position = next;
        }
    }
}
