package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.ContentPackManifest;
import com.chtholly.seed.contentpack.model.SeedAccountDefinition;
import com.chtholly.seed.contentpack.model.SeedCommentDefinition;
import com.chtholly.seed.contentpack.model.SeedPostDefinition;
import com.chtholly.seed.contentpack.model.SeedReactionDefinition;
import com.chtholly.seed.contentpack.model.SeedViewDefinition;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;

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
