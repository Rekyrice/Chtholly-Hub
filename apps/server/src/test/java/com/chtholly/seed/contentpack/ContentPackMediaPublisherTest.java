package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.ContentPackMediaPublisher.PublishedAsset;
import com.chtholly.seed.contentpack.ContentPackMediaPublisher.PublishedContent;
import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.ContentPackManifest;
import com.chtholly.seed.contentpack.model.SeedAssetDefinition;
import com.chtholly.seed.contentpack.model.SeedPostDefinition;
import com.chtholly.seed.contentpack.model.SeedPostDefinition.ArticleBrief;
import com.chtholly.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContentPackMediaPublisherTest {

    private static final byte[] WEBP = "RIFFxxxxWEBPseed-content".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path root;

    private StorageService storage;
    private ContentPackMediaPublisher publisher;

    @BeforeEach
    void setUp() {
        storage = mock(StorageService.class);
        publisher = new ContentPackMediaPublisher(storage);
    }

    @Test
    void givenValidAsset_whenPublish_thenUploadsDeclaredContentAddressedKey() throws Exception {
        SeedAssetDefinition asset = writeAsset("avatar-night", "media/avatar.webp", WEBP);
        when(storage.resolvePublicUrl(asset.objectKey())).thenReturn("/uploads/" + asset.objectKey());

        PublishedAsset result = publisher.publish(root, asset);

        verify(storage).uploadVerifiedObject(eq(asset.objectKey()), any(InputStream.class),
                eq("image/webp"), eq((long) WEBP.length), eq(asset.sha256()));
        assertThat(result.publicUrl()).isEqualTo("/uploads/" + asset.objectKey());
        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.objectKey()).contains(result.sha256());
    }

    @Test
    void givenHashMismatch_whenPublish_thenDoesNotTouchStorage() throws Exception {
        SeedAssetDefinition valid = writeAsset("avatar-night", "media/avatar.webp", WEBP);
        SeedAssetDefinition wrong = new SeedAssetDefinition(
                valid.key(), valid.source(), valid.sourceUrl(), valid.sourceFile(), valid.file(),
                valid.objectKey(), "0".repeat(64), valid.contentType(), valid.width(), valid.height(), valid.usage());

        assertThatThrownBy(() -> publisher.publish(root, wrong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sha256 mismatch")
                .hasMessageContaining("avatar-night");
        verifyNoInteractions(storage);
    }

    @Test
    void givenMimeMismatch_whenPublish_thenDoesNotTouchStorage() throws Exception {
        SeedAssetDefinition asset = writeAsset(
                "fake-webp", "media/fake.webp", "not-a-webp".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> publisher.publish(root, asset))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIME mismatch");
        verifyNoInteractions(storage);
    }

    @Test
    void givenObjectKeyWithoutHash_whenPublish_thenDoesNotTouchStorage() throws Exception {
        SeedAssetDefinition valid = writeAsset("avatar-night", "media/avatar.webp", WEBP);
        SeedAssetDefinition unsafe = new SeedAssetDefinition(
                valid.key(), valid.source(), valid.sourceUrl(), valid.sourceFile(), valid.file(),
                "seed/content-v2/avatars/avatar-night.webp", valid.sha256(), valid.contentType(),
                valid.width(), valid.height(), valid.usage());

        assertThatThrownBy(() -> publisher.publish(root, unsafe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content-addressed object key");
        verifyNoInteractions(storage);
    }

    @Test
    void givenPathOutsidePack_whenPublish_thenDoesNotTouchStorage() throws Exception {
        Path outside = root.getParent().resolve("outside.webp");
        Files.write(outside, WEBP);
        String hash = sha256(WEBP);
        SeedAssetDefinition asset = asset(
                "outside", "../outside.webp", "seed/content-v2/assets/outside-" + hash.substring(0, 8) + ".webp", hash);

        assertThatThrownBy(() -> publisher.publish(root, asset))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes pack root");
        verifyNoInteractions(storage);
    }

    @Test
    void givenPack_whenPublishAll_thenPublishesAssetsBeforeFinalMarkdown() throws Exception {
        SeedAssetDefinition asset = writeAsset("cover-one", "media/cover.webp", WEBP);
        SeedPostDefinition post = withInlineAssets(
                post("post-one", "正文 ![图](asset:cover-one)"), List.of("cover-one"));
        ContentPack pack = pack(asset, post);
        String assetUrl = "/uploads/" + asset.objectKey();
        String finalMarkdown = "正文 ![图](" + assetUrl + ")";
        String markdownKey = markdownKey(post.seedKey(), finalMarkdown);
        when(storage.resolvePublicUrl(asset.objectKey())).thenReturn(assetUrl);
        when(storage.resolvePublicUrl(markdownKey)).thenReturn("/uploads/" + markdownKey);

        PublishedContent result = publisher.publishAll(pack);

        var ordered = inOrder(storage);
        ordered.verify(storage).uploadVerifiedObject(
                eq(asset.objectKey()), any(InputStream.class), eq("image/webp"), anyLong(), eq(asset.sha256()));
        ordered.verify(storage).uploadVerifiedObject(eq(markdownKey), any(InputStream.class),
                eq("text/markdown; charset=utf-8"), anyLong(), any());
        assertThat(result.assets()).containsKey("cover-one");
        assertThat(result.markdownByPost()).containsKey("post-one");
        assertThat(result.newObjectKeys()).containsExactly(asset.objectKey(), markdownKey);
        publisher.commitPublishedObjects(result);
    }

    @Test
    void givenUrlResolutionFailsAfterUpload_whenPublish_thenDeletesNewObject() throws Exception {
        SeedAssetDefinition asset = writeAsset("avatar-night", "media/avatar.webp", WEBP);
        when(storage.resolvePublicUrl(asset.objectKey())).thenThrow(new IllegalStateException("bad public URL"));

        assertThatThrownBy(() -> publisher.publish(root, asset))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad public URL");

        verify(storage).deleteObject(asset.objectKey());
    }

    @Test
    void givenBlankPublicUrlAfterUpload_whenPublish_thenRejectsAndDeletesNewObject() throws Exception {
        SeedAssetDefinition asset = writeAsset("avatar-night", "media/avatar.webp", WEBP);
        when(storage.resolvePublicUrl(asset.objectKey())).thenReturn(" ");

        assertThatThrownBy(() -> publisher.publish(root, asset))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public URL");

        verify(storage).deleteObject(asset.objectKey());
    }

    @Test
    void givenPreexistingObjectAndLaterFailure_whenPublishAll_thenRollsBackOnlyNewObjects() throws Exception {
        SeedAssetDefinition existing = writeAsset("cover-one", "media/existing.webp", WEBP);
        byte[] secondBytes = "RIFFxxxxWEBPsecond".getBytes(StandardCharsets.US_ASCII);
        SeedAssetDefinition created = writeAsset("inline-two", "media/created.webp", secondBytes);
        SeedPostDefinition post = post("post-one", "正文");
        ContentPack pack = pack(List.of(existing, created), post);
        String markdownKey = markdownKey(post);
        when(storage.objectExists(existing.objectKey())).thenReturn(true);
        when(storage.objectMatches(existing.objectKey(), existing.sha256(), WEBP.length)).thenReturn(true);
        when(storage.resolvePublicUrl(existing.objectKey())).thenReturn("/uploads/" + existing.objectKey());
        when(storage.resolvePublicUrl(created.objectKey())).thenReturn("/uploads/" + created.objectKey());
        doThrow(new IllegalStateException("storage unavailable"))
                .when(storage).uploadVerifiedObject(eq(markdownKey), any(InputStream.class), any(), anyLong(), any());

        assertThatThrownBy(() -> publisher.publishAll(pack))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage unavailable");

        verify(storage, never()).uploadVerifiedObject(eq(existing.objectKey()), any(), any(), anyLong(), any());
        verify(storage, never()).deleteObject(existing.objectKey());
        verify(storage).deleteObject(created.objectKey());
        verify(storage).deleteObject(markdownKey);
    }

    @Test
    void givenPublishedContent_whenRollback_thenDeletesObjectsCreatedByThatPublication() throws Exception {
        SeedAssetDefinition asset = writeAsset("cover-one", "media/cover.webp", WEBP);
        SeedPostDefinition post = post("post-one", "正文");
        ContentPack pack = pack(asset, post);
        String markdownKey = markdownKey(post);
        when(storage.resolvePublicUrl(asset.objectKey())).thenReturn("/uploads/" + asset.objectKey());
        when(storage.resolvePublicUrl(markdownKey)).thenReturn("/uploads/" + markdownKey);
        PublishedContent content = publisher.publishAll(pack);

        publisher.rollbackNewObjects(content);

        verify(storage).deleteObject(asset.objectKey());
        verify(storage).deleteObject(markdownKey);
        assertThatThrownBy(() -> publisher.rollbackNewObjects(content))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already released");
    }

    @Test
    void givenExistingObjectWithWrongDigest_whenPublish_thenRejectsWithoutOverwrite() throws Exception {
        SeedAssetDefinition asset = writeAsset("avatar-night", "media/avatar.webp", WEBP);
        when(storage.objectExists(asset.objectKey())).thenReturn(true);
        when(storage.objectMatches(asset.objectKey(), asset.sha256(), WEBP.length)).thenReturn(false);

        assertThatThrownBy(() -> publisher.publish(root, asset))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existing object mismatch");

        verify(storage, never()).uploadVerifiedObject(any(), any(), any(), anyLong(), any());
        verify(storage, never()).resolvePublicUrl(any());
    }

    @Test
    void givenSameNamespace_whenFirstLeaseOpen_thenSecondPublishWaitsUntilRollback() throws Exception {
        Path storageRoot = root.resolveSibling("published-storage");
        var props = new com.chtholly.storage.config.StorageProperties();
        props.getLocal().setBasePath(storageRoot.toString());
        props.getLocal().setPublicUrlPrefix("/uploads");
        var localStorage = new com.chtholly.storage.LocalFileStorageService(props);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(localStorage, "init");
        ContentPackMediaPublisher first = new ContentPackMediaPublisher(localStorage);
        ContentPackMediaPublisher second = new ContentPackMediaPublisher(localStorage);
        SeedAssetDefinition asset = writeAsset("cover-one", "media/cover.webp", WEBP);
        ContentPack pack = pack(asset, post("post-one", "正文"));
        PublishedContent firstPublication = first.publishAll(pack);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var secondFuture = executor.submit(() -> second.publishAll(pack));
            assertThatThrownBy(() -> secondFuture.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            first.rollbackNewObjects(firstPublication);
            PublishedContent secondPublication = secondFuture.get(5, TimeUnit.SECONDS);
            second.commitPublishedObjects(secondPublication);

            assertThat(localStorage.objectExists(asset.objectKey())).isTrue();
            assertThat(localStorage.objectExists(markdownKey(pack.posts().getFirst()))).isTrue();
        }
    }

    @Test
    void givenCommittedPublication_whenCommitAgain_thenRejectsDoubleRelease() throws Exception {
        SeedAssetDefinition asset = writeAsset("cover-one", "media/cover.webp", WEBP);
        SeedPostDefinition post = post("post-one", "正文");
        ContentPack pack = pack(asset, post);
        when(storage.resolvePublicUrl(asset.objectKey())).thenReturn("/uploads/" + asset.objectKey());
        when(storage.resolvePublicUrl(markdownKey(post))).thenReturn("/uploads/" + markdownKey(post));
        PublishedContent content = publisher.publishAll(pack);

        publisher.commitPublishedObjects(content);

        assertThatThrownBy(() -> publisher.commitPublishedObjects(content))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already released");
    }

    @Test
    void givenMismatchedLeaseToken_whenCommit_thenRejectsWithoutReleasingOwner() throws Exception {
        SeedAssetDefinition asset = writeAsset("cover-one", "media/cover.webp", WEBP);
        SeedPostDefinition post = post("post-one", "正文");
        ContentPack pack = pack(asset, post);
        when(storage.resolvePublicUrl(asset.objectKey())).thenReturn("/uploads/" + asset.objectKey());
        when(storage.resolvePublicUrl(markdownKey(post))).thenReturn("/uploads/" + markdownKey(post));
        PublishedContent owner = publisher.publishAll(pack);
        PublishedContent forged = new PublishedContent(
                owner.assets(), owner.markdownByPost(), owner.newObjectKeys(), owner.namespace(), "wrong-token");

        assertThatThrownBy(() -> publisher.commitPublishedObjects(forged))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease token mismatch");

        publisher.commitPublishedObjects(owner);
    }

    @Test
    void givenAssetReferenceInsideCode_whenPublishAll_thenLeavesCodeUntouched() throws Exception {
        SeedAssetDefinition asset = writeAsset("cover-one", "media/cover.webp", WEBP);
        String markdown = "正文\n\n`[inline](asset:cover-one)`\n\n```md\n![fenced](asset:cover-one)\n```";
        SeedPostDefinition post = post("post-one", markdown);
        ContentPack pack = pack(asset, post);
        String markdownObjectKey = markdownKey(post.seedKey(), markdown);
        when(storage.resolvePublicUrl(asset.objectKey())).thenReturn("/uploads/" + asset.objectKey());
        when(storage.resolvePublicUrl(markdownObjectKey)).thenReturn("/uploads/" + markdownObjectKey);

        PublishedContent content = publisher.publishAll(pack);

        assertThat(content.markdownByPost().get(post.seedKey()).sha256())
                .isEqualTo(sha256(markdown.getBytes(StandardCharsets.UTF_8)));
        publisher.commitPublishedObjects(content);
    }

    @Test
    void givenDeclaredInlineAssetNotReferenced_whenPublishAll_thenRejectsBeforeMarkdownUpload() throws Exception {
        SeedAssetDefinition asset = writeAsset("cover-one", "media/cover.webp", WEBP);
        SeedPostDefinition base = post("post-one", "正文");
        SeedPostDefinition post = new SeedPostDefinition(
                base.seedKey(), base.legacySlug(), base.authorSeedKey(), base.title(), base.slug(), base.description(),
                base.category(), base.tags(), base.publishTime(), base.markdownFile(), base.coverAsset(),
                List.of(asset.key()), base.brief(), base.markdown());
        ContentPack pack = pack(asset, post);
        when(storage.resolvePublicUrl(asset.objectKey())).thenReturn("/uploads/" + asset.objectKey());

        assertThatThrownBy(() -> publisher.publishAll(pack))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inlineAssets mismatch");

        verify(storage, never()).uploadVerifiedObject(any(), any(), eq("text/markdown; charset=utf-8"), anyLong(), any());
    }

    private SeedAssetDefinition writeAsset(String key, String file, byte[] bytes) throws Exception {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        String hash = sha256(bytes);
        return asset(key, file, "seed/content-v2/assets/" + key + "-" + hash + ".webp", hash);
    }

    private SeedAssetDefinition asset(String key, String file, String objectKey, String hash) {
        return new SeedAssetDefinition(
                key, "local", null, "source.png", file, objectKey, hash,
                "image/webp", 512, 512, "avatar");
    }

    private SeedPostDefinition post(String seedKey, String markdown) {
        return new SeedPostDefinition(
                seedKey, null, "author", "title", seedKey, "足够长度的文章简介文本", "动漫",
                List.of("tag"), Instant.parse("2026-01-01T00:00:00Z"), "posts/" + seedKey + ".md",
                "cover-one", List.of(), new ArticleBrief(List.of(), "voice", "position", "format", List.of(), List.of()),
                markdown);
    }

    private ContentPack pack(SeedAssetDefinition asset, SeedPostDefinition post) {
        return pack(List.of(asset), post);
    }

    private SeedPostDefinition withInlineAssets(SeedPostDefinition post, List<String> inlineAssets) {
        return new SeedPostDefinition(
                post.seedKey(), post.legacySlug(), post.authorSeedKey(), post.title(), post.slug(), post.description(),
                post.category(), post.tags(), post.publishTime(), post.markdownFile(), post.coverAsset(), inlineAssets,
                post.brief(), post.markdown());
    }

    private ContentPack pack(List<SeedAssetDefinition> assets, SeedPostDefinition post) {
        Map<String, SeedAssetDefinition> indexed = assets.stream()
                .collect(java.util.stream.Collectors.toMap(SeedAssetDefinition::key, value -> value));
        return new ContentPack(root,
                new ContentPackManifest("v2", "seed", "review", 0, 1, Map.of("动漫", 1)),
                List.of(), indexed, List.of(post), List.of(), List.of(), List.of(), List.of());
    }

    private String markdownKey(SeedPostDefinition post) throws Exception {
        return markdownKey(post.seedKey(), post.markdown());
    }

    private String markdownKey(String postSeedKey, String markdown) throws Exception {
        String hash = sha256(markdown.getBytes(StandardCharsets.UTF_8));
        return "seed/content-v2/posts/" + postSeedKey + "-" + hash + ".md";
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
