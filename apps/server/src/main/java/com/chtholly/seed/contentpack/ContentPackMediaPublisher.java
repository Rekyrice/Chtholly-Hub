package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.SeedAssetDefinition;
import com.chtholly.seed.contentpack.model.SeedPostDefinition;
import com.chtholly.storage.StorageObjectKeyValidator;
import com.chtholly.storage.StorageService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Publishes verified content-pack media and Markdown through the configured storage boundary.
 *
 * <p>A JVM-wide namespace lease spans media publication and the later database decision. Task 7
 * must finish every successful {@link #publishAll(ContentPack)} call with exactly one call to
 * {@link #commitPublishedObjects(PublishedContent)} or {@link #rollbackNewObjects(PublishedContent)}.
 * This prevents one importer from deleting objects already accepted by another importer in the
 * same application process. A deployment running importers in multiple JVMs must additionally
 * serialize the import command at the infrastructure boundary.
 */
@Component
public final class ContentPackMediaPublisher {

    private static final String MARKDOWN_CONTENT_TYPE = "text/markdown; charset=utf-8";
    private static final int SHA256_HEX_LENGTH = 64;
    private static final Pattern MARKDOWN_ASSET = Pattern.compile(
            "([!]?\\[[^\\]\\r\\n]*]\\(\\s*)asset:([A-Za-z0-9_-]+)(\\s*(?:\"[^\"\\r\\n]*\")?\\))"
                    + "|\\{\\{asset:([A-Za-z0-9_-]+)}}");
    private static final ConcurrentMap<String, Semaphore> NAMESPACE_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> ACTIVE_LEASES = new ConcurrentHashMap<>();

    private final StorageService storage;

    /**
     * Creates a publisher backed by the application's selected storage implementation.
     *
     * @param storage local or OSS storage service
     */
    public ContentPackMediaPublisher(StorageService storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /**
     * Acquires the pack namespace lease and publishes assets followed by final Markdown.
     *
     * @param pack loaded and validated content pack
     * @return immutable URLs, owned keys and the lease token for the Task 7 lifecycle
     * @throws IOException when local content cannot be read or storage verification fails
     */
    public PublishedContent publishAll(ContentPack pack) throws IOException {
        Objects.requireNonNull(pack, "pack");
        String namespace = requireText(pack.manifest().namespace(), "content pack namespace");
        PublicationLease lease = acquireLease(namespace);
        Map<String, PublishedAsset> assets = new LinkedHashMap<>();
        Map<String, PublishedAsset> markdownByPost = new LinkedHashMap<>();
        List<String> newObjectKeys = new ArrayList<>();
        try {
            Path root = realRoot(pack.root());
            Path assetRoot = resolveAssetRoot(root);
            String version = StorageObjectKeyValidator.requireContentPackVersion(pack.manifest().version());
            for (SeedAssetDefinition asset : pack.assets().values()) {
                PublishedAsset published = publish(assetRoot, asset, version);
                assets.put(asset.key(), published);
                recordNewObject(published, newObjectKeys);
            }
            for (SeedPostDefinition post : pack.posts()) {
                PublishedAsset published = publishMarkdown(post, assets, version);
                markdownByPost.put(post.seedKey(), published);
                recordNewObject(published, newObjectKeys);
            }
            return new PublishedContent(assets, markdownByPost, newObjectKeys, namespace, lease.token());
        } catch (IOException | RuntimeException exception) {
            try {
                cleanup(newObjectKeys, exception);
            } finally {
                releaseAfterInternalFailure(lease, exception);
            }
            throw exception;
        }
    }

    /**
     * Publishes one normalized media asset after validating its path, hash and MIME signature.
     *
     * @param root content-pack root
     * @param asset declared normalized asset
     * @return immutable publication metadata
     * @throws IOException when the asset cannot be read, verified or uploaded
     */
    public PublishedAsset publish(Path root, SeedAssetDefinition asset) throws IOException {
        Path realRoot = realRoot(root);
        return publish(realRoot, asset,
                StorageObjectKeyValidator.requireContentPackVersion(realRoot.getFileName().toString()));
    }

    private PublishedAsset publish(Path realRoot, SeedAssetDefinition asset, String version) throws IOException {
        Objects.requireNonNull(asset, "asset");
        byte[] bytes = readOnceInside(realRoot, asset.file(), asset.key());
        if (bytes.length == 0) {
            throw new IllegalArgumentException("empty media asset: " + asset.key());
        }
        String hash = sha256(bytes);
        String declaredHash = requireSha256(asset.sha256(), "sha256 for " + asset.key());
        if (!hash.equalsIgnoreCase(declaredHash)) {
            throw new IllegalArgumentException(
                    "sha256 mismatch for " + asset.key() + ": declared " + declaredHash + ", actual " + hash);
        }
        String contentType = requireText(asset.contentType(), "contentType for " + asset.key())
                .trim().toLowerCase(Locale.ROOT);
        if (!matchesMime(bytes, contentType)) {
            throw new IllegalArgumentException("MIME mismatch for " + asset.key() + ": " + contentType);
        }
        validateContentAddressedKey(asset.objectKey(), hash, asset.key(), objectPrefix(version));
        return store(asset.key(), asset.objectKey(), hash, contentType, bytes);
    }

    /**
     * Marks a successful database write as accepting the published objects and releases its lease.
     *
     * <p>Task 7 calls this immediately after the database transaction commits. Calling it twice,
     * or with a copied result carrying the wrong token, is rejected.
     *
     * @param content publication accepted by the database transaction
     */
    public void commitPublishedObjects(PublishedContent content) {
        Objects.requireNonNull(content, "content");
        releaseLease(content.namespace(), content.leaseToken());
    }

    /**
     * Best-effort recovery hook for an importer whose commit-release call threw unexpectedly.
     *
     * <p>An already released lease is accepted, while a live lease owned by another token remains
     * protected. This method never deletes published objects because MySQL has already committed.
     *
     * @param content publication already accepted by the database
     */
    public void ensurePublicationLeaseReleased(PublishedContent content) {
        Objects.requireNonNull(content, "content");
        String activeToken = ACTIVE_LEASES.get(content.namespace());
        if (activeToken == null) {
            return;
        }
        if (!activeToken.equals(content.leaseToken())) {
            throw new IllegalStateException(
                    "publication lease token mismatch for namespace: " + content.namespace());
        }
        releaseLease(content.namespace(), content.leaseToken());
    }

    /**
     * Removes objects owned by a failed database write and then releases its namespace lease.
     *
     * @param content publication rejected by the database transaction
     */
    public void rollbackNewObjects(PublishedContent content) {
        Objects.requireNonNull(content, "content");
        assertLeaseOwner(content.namespace(), content.leaseToken());
        try {
            cleanup(content.newObjectKeys(), null);
        } finally {
            releaseLease(content.namespace(), content.leaseToken());
        }
    }

    private PublishedAsset publishMarkdown(
            SeedPostDefinition post,
            Map<String, PublishedAsset> assets,
            String version) throws IOException {
        RewrittenMarkdown rewritten = rewriteAssetReferences(post, assets);
        if (!rewritten.assetKeys().equals(post.inlineAssets())) {
            throw new IllegalArgumentException(
                    "inlineAssets mismatch for " + post.seedKey() + ": declared " + post.inlineAssets()
                            + ", referenced " + rewritten.assetKeys());
        }
        byte[] bytes = rewritten.markdown().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            throw new IllegalArgumentException("empty Markdown: " + post.seedKey());
        }
        String hash = sha256(bytes);
        String objectKey = objectPrefix(version) + "posts/" + post.seedKey() + "-" + hash + ".md";
        validateContentAddressedKey(objectKey, hash, post.seedKey(), objectPrefix(version));
        return store(post.seedKey(), objectKey, hash, MARKDOWN_CONTENT_TYPE, bytes);
    }

    private RewrittenMarkdown rewriteAssetReferences(
            SeedPostDefinition post,
            Map<String, PublishedAsset> assets) {
        String markdown = requireText(post.markdown(), "Markdown for " + post.seedKey());
        StringBuilder rewritten = new StringBuilder(markdown.length());
        List<String> assetKeys = new ArrayList<>();
        Character fenceMarker = null;
        for (String line : markdown.split("(?<=\\n)", -1)) {
            String trimmed = line.stripLeading();
            Character marker = fenceMarker(trimmed);
            if (fenceMarker == null && marker != null) {
                fenceMarker = marker;
                rewritten.append(line);
            } else if (fenceMarker != null) {
                rewritten.append(line);
                if (marker != null && marker.equals(fenceMarker)) {
                    fenceMarker = null;
                }
            } else {
                rewritten.append(replaceOutsideInlineCode(line, assets, assetKeys, post.seedKey()));
            }
        }
        return new RewrittenMarkdown(rewritten.toString(), List.copyOf(assetKeys));
    }

    private String replaceOutsideInlineCode(
            String line,
            Map<String, PublishedAsset> assets,
            List<String> assetKeys,
            String postSeedKey) {
        StringBuilder result = new StringBuilder(line.length());
        int segmentStart = 0;
        int index = 0;
        int codeDelimiterLength = 0;
        while (index < line.length()) {
            if (line.charAt(index) != '`') {
                index++;
                continue;
            }
            int runEnd = index + 1;
            while (runEnd < line.length() && line.charAt(runEnd) == '`') {
                runEnd++;
            }
            int runLength = runEnd - index;
            if (codeDelimiterLength == 0) {
                result.append(replaceMarkup(line.substring(segmentStart, index), assets, assetKeys, postSeedKey));
                result.append(line, index, runEnd);
                codeDelimiterLength = runLength;
                segmentStart = runEnd;
            } else if (runLength == codeDelimiterLength) {
                result.append(line, segmentStart, runEnd);
                codeDelimiterLength = 0;
                segmentStart = runEnd;
            }
            index = runEnd;
        }
        if (codeDelimiterLength == 0) {
            result.append(replaceMarkup(line.substring(segmentStart), assets, assetKeys, postSeedKey));
        } else {
            result.append(line.substring(segmentStart));
        }
        return result.toString();
    }

    private String replaceMarkup(
            String text,
            Map<String, PublishedAsset> assets,
            List<String> assetKeys,
            String postSeedKey) {
        Matcher matcher = MARKDOWN_ASSET.matcher(text);
        StringBuilder rewritten = new StringBuilder(text.length());
        while (matcher.find()) {
            String assetKey = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
            PublishedAsset asset = assets.get(assetKey);
            if (asset == null) {
                throw new IllegalArgumentException(
                        "unknown Markdown asset reference for " + postSeedKey + ": " + assetKey);
            }
            assetKeys.add(assetKey);
            String replacement = matcher.group(2) != null
                    ? matcher.group(1) + asset.publicUrl() + matcher.group(3)
                    : asset.publicUrl();
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private PublishedAsset store(
            String key,
            String objectKey,
            String hash,
            String contentType,
            byte[] bytes) throws IOException {
        boolean existed = storage.objectExists(objectKey);
        try {
            if (existed) {
                if (!storage.objectMatches(objectKey, hash, bytes.length)) {
                    throw new IllegalStateException("existing object mismatch for " + key + ": " + objectKey);
                }
            } else {
                try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                    storage.uploadVerifiedObject(objectKey, input, contentType, bytes.length, hash);
                }
            }
            String publicUrl = requireText(storage.resolvePublicUrl(objectKey), "public URL for " + key);
            return new PublishedAsset(key, objectKey, publicUrl, hash, contentType, bytes.length, !existed);
        } catch (IOException | RuntimeException exception) {
            if (!existed) {
                cleanup(List.of(objectKey), exception);
            }
            throw exception;
        }
    }

    private Path realRoot(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        Path realRoot = root.toRealPath();
        if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("content pack root is not a directory: " + root);
        }
        return realRoot;
    }

    private Path resolveAssetRoot(Path packRoot) throws IOException {
        Path declaredAssets = packRoot.resolve("assets").normalize();
        if (!Files.exists(declaredAssets, LinkOption.NOFOLLOW_LINKS)) {
            return packRoot;
        }
        if (Files.isSymbolicLink(declaredAssets)) {
            throw new IllegalArgumentException("content pack assets directory is a symbolic link: " + declaredAssets);
        }
        Path assetRoot = realRoot(declaredAssets);
        if (!assetRoot.startsWith(packRoot)) {
            throw new IllegalArgumentException("content pack assets directory escapes pack root: " + declaredAssets);
        }
        return assetRoot;
    }

    private byte[] readOnceInside(Path realRoot, String relative, String assetKey) throws IOException {
        String requiredRelative = requireText(relative, "file for " + assetKey);
        Path resolved = realRoot.resolve(requiredRelative).normalize();
        if (!resolved.startsWith(realRoot)) {
            throw new IllegalArgumentException("media path escapes pack root: " + requiredRelative);
        }
        if (Files.isSymbolicLink(resolved)) {
            throw new IllegalArgumentException("media path is a symbolic link: " + requiredRelative);
        }
        Path realFile = resolved.toRealPath();
        if (!realFile.startsWith(realRoot)) {
            throw new IllegalArgumentException("media path escapes pack root: " + requiredRelative);
        }
        BasicFileAttributes before = Files.readAttributes(
                realFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) {
            throw new IllegalArgumentException("media file is not regular: " + requiredRelative);
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(
                realFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readAllBytes();
        }
        BasicFileAttributes after = Files.readAttributes(
                realFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() != bytes.length
                || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("media file changed while reading: " + assetKey);
        }
        return bytes;
    }

    private void validateContentAddressedKey(String objectKey, String hash, String key, String objectPrefix) {
        String requiredKey = requireText(objectKey, "objectKey for " + key);
        StorageObjectKeyValidator.assertSafeObjectKey(requiredKey);
        String hashSuffix = "-" + hash.toLowerCase(Locale.ROOT);
        int extensionIndex = requiredKey.lastIndexOf('.');
        boolean containsFullHash = extensionIndex > 0
                && requiredKey.substring(0, extensionIndex).toLowerCase(Locale.ROOT).endsWith(hashSuffix);
        if (!requiredKey.startsWith(objectPrefix) || !containsFullHash) {
            throw new IllegalArgumentException("invalid content-addressed object key for " + key + ": " + requiredKey);
        }
    }

    private String objectPrefix(String version) {
        return StorageObjectKeyValidator.contentPackObjectPrefix(version);
    }

    private boolean matchesMime(byte[] bytes, String contentType) {
        return switch (contentType) {
            case "image/webp" -> bytes.length >= 12
                    && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            case "image/png" -> bytes.length >= 8
                    && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                    && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
            case "image/jpeg" -> bytes.length >= 3
                    && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "image/gif" -> bytes.length >= 6
                    && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8'
                    && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a';
            default -> false;
        };
    }

    private PublicationLease acquireLease(String namespace) {
        Semaphore semaphore = NAMESPACE_LOCKS.computeIfAbsent(namespace, ignored -> new Semaphore(1, true));
        semaphore.acquireUninterruptibly();
        String token = UUID.randomUUID().toString();
        String previous = ACTIVE_LEASES.putIfAbsent(namespace, token);
        if (previous != null) {
            semaphore.release();
            throw new IllegalStateException("publication lease registry is inconsistent for namespace: " + namespace);
        }
        return new PublicationLease(namespace, token);
    }

    private void assertLeaseOwner(String namespace, String token) {
        String activeToken = ACTIVE_LEASES.get(namespace);
        if (activeToken == null) {
            throw new IllegalStateException("publication lease already released for namespace: " + namespace);
        }
        if (!activeToken.equals(token)) {
            throw new IllegalStateException("publication lease token mismatch for namespace: " + namespace);
        }
    }

    private void releaseLease(String namespace, String token) {
        assertLeaseOwner(namespace, token);
        if (!ACTIVE_LEASES.remove(namespace, token)) {
            throw new IllegalStateException("publication lease changed while releasing namespace: " + namespace);
        }
        Semaphore semaphore = NAMESPACE_LOCKS.get(namespace);
        if (semaphore == null) {
            throw new IllegalStateException("publication semaphore missing for namespace: " + namespace);
        }
        semaphore.release();
    }

    private void releaseAfterInternalFailure(PublicationLease lease, Throwable original) {
        try {
            releaseLease(lease.namespace(), lease.token());
        } catch (RuntimeException exception) {
            original.addSuppressed(exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void recordNewObject(PublishedAsset published, List<String> newObjectKeys) {
        if (published.newlyCreated()) {
            newObjectKeys.add(published.objectKey());
        }
    }

    private void cleanup(List<String> objectKeys, Throwable original) {
        RuntimeException cleanupFailure = null;
        for (int index = objectKeys.size() - 1; index >= 0; index--) {
            try {
                storage.deleteObject(objectKeys.get(index));
            } catch (RuntimeException exception) {
                if (cleanupFailure == null) {
                    cleanupFailure = exception;
                } else {
                    cleanupFailure.addSuppressed(exception);
                }
            }
        }
        if (cleanupFailure != null) {
            if (original != null) {
                original.addSuppressed(cleanupFailure);
            } else {
                throw cleanupFailure;
            }
        }
    }

    private String requireSha256(String value, String field) {
        String sha256 = requireText(value, field);
        if (sha256.length() != SHA256_HEX_LENGTH || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " must contain 64 hexadecimal characters");
        }
        return sha256;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private Character fenceMarker(String trimmedLine) {
        if (startsWithAtLeastThree(trimmedLine, '`')) {
            return '`';
        }
        if (startsWithAtLeastThree(trimmedLine, '~')) {
            return '~';
        }
        return null;
    }

    private boolean startsWithAtLeastThree(String value, char marker) {
        return value.length() >= 3
                && value.charAt(0) == marker
                && value.charAt(1) == marker
                && value.charAt(2) == marker;
    }

    /**
     * Immutable metadata for one published image or Markdown object.
     *
     * @param key content-pack asset key or post seed key
     * @param objectKey full-SHA-256 content-addressed storage key
     * @param publicUrl stable public URL
     * @param sha256 full lowercase SHA-256
     * @param contentType verified MIME type
     * @param size byte length
     * @param newlyCreated whether this publication uploaded the object
     */
    public record PublishedAsset(
            String key,
            String objectKey,
            String publicUrl,
            String sha256,
            String contentType,
            long size,
            boolean newlyCreated) {
    }

    /**
     * Immutable media result and active publication lease consumed by Task 7.
     *
     * @param assets published image assets indexed by asset key
     * @param markdownByPost published final Markdown indexed by post seed key
     * @param newObjectKeys objects absent before this publication, in creation order
     * @param namespace serialized content-pack namespace
     * @param leaseToken opaque token that authorizes one commit or rollback
     */
    public record PublishedContent(
            Map<String, PublishedAsset> assets,
            Map<String, PublishedAsset> markdownByPost,
            List<String> newObjectKeys,
            String namespace,
            String leaseToken) {

        /**
         * Protects publication state from caller mutation.
         */
        public PublishedContent {
            assets = Map.copyOf(assets);
            markdownByPost = Map.copyOf(markdownByPost);
            newObjectKeys = List.copyOf(newObjectKeys);
            if (namespace == null || namespace.isBlank() || leaseToken == null || leaseToken.isBlank()) {
                throw new IllegalArgumentException("publication namespace and lease token must not be blank");
            }
        }
    }

    private record PublicationLease(String namespace, String token) {
    }

    private record RewrittenMarkdown(String markdown, List<String> assetKeys) {
    }
}
