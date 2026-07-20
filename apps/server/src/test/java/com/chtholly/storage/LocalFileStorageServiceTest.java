package com.chtholly.storage;

import com.chtholly.storage.config.StorageProperties;
import com.chtholly.seed.SeedProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalFileStorageServiceTest {

    @TempDir
    Path tempDir;

    @TempDir
    Path outsideDir;

    private LocalFileStorageService service;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.getLocal().setBasePath(tempDir.toString());
        props.getLocal().setPublicUrlPrefix("/uploads");
        service = new LocalFileStorageService(props, new SeedProperties());
        service.init();
    }

    @Test
    void init_whenCliReadOnly_thenNormalizesBasePathWithoutCreatingDirectory() {
        Path missing = tempDir.resolve("read-only/missing");
        StorageProperties props = new StorageProperties();
        props.getLocal().setBasePath(missing.toString());
        SeedProperties seed = new SeedProperties();
        seed.setCliReadOnly(true);
        LocalFileStorageService readOnly = new LocalFileStorageService(props, seed);

        readOnly.init();

        assertThat(Files.exists(missing)).isFalse();
        assertThat(readOnly.resolveObjectPath("posts/1/content.md"))
                .isEqualTo(missing.toAbsolutePath().normalize().resolve("posts/1/content.md"));
    }

    @Test
    void uploadAvatar_writesFileAndReturnsPublicUrl() throws Exception {
        byte[] pngHeader = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0
        };

        String url = service.uploadAvatar(42L, new ByteArrayInputStream(pngHeader), "image/png");

        assertThat(url).startsWith("/uploads/avatars/42/");
        assertThat(url).endsWith(".png");
        Path stored = service.resolveObjectPath(url.replace("/uploads/", ""));
        assertThat(Files.exists(stored)).isTrue();
    }

    @Test
    void generatePresignedPutUrl_returnsLocalUploadEndpoint() {
        PresignedUrl presigned = service.generatePresignedPutUrl("posts/1/content.md", "text/markdown");

        assertThat(presigned.url()).isEqualTo("/api/v1/storage/upload");
        assertThat(presigned.method()).isEqualTo("POST");
        assertThat(presigned.headers()).containsEntry("Content-Type", "text/markdown");
    }

    @Test
    void uploadObject_andDeleteObject() throws Exception {
        String key = "posts/99/content.md";
        byte[] data = "# hello".getBytes(StandardCharsets.UTF_8);
        assertThat(service.objectExists(key)).isFalse();

        service.uploadObject(key, new ByteArrayInputStream(data), "text/markdown", data.length);

        Path target = service.resolveObjectPath(key);
        assertThat(Files.readString(target)).isEqualTo("# hello");
        assertThat(service.objectExists(key)).isTrue();

        service.deleteObject(key);
        assertThat(Files.exists(target)).isFalse();
        assertThat(service.objectExists(key)).isFalse();
    }

    @Test
    void uploadVerifiedObject_rejectsDeclaredSizeMismatchAndRemovesTempFile() {
        String key = "seed/content-v2/posts/post-" + "a".repeat(64) + ".md";
        byte[] data = "short".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.uploadVerifiedObject(
                key, new ByteArrayInputStream(data), "text/markdown", data.length + 1L, sha256(data)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("size mismatch");

        assertThat(service.objectExists(key)).isFalse();
        assertThat(findUploadTemps()).isEmpty();
    }

    @Test
    void uploadVerifiedObject_whenImmutableTargetDiffers_thenKeepsOriginalAndFails() throws Exception {
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "replacement".getBytes(StandardCharsets.UTF_8);
        String key = "seed/content-v2/posts/post-" + sha256(original) + ".md";
        service.uploadVerifiedObject(
                key, new ByteArrayInputStream(original), "text/markdown", original.length, sha256(original));

        assertThatThrownBy(() -> service.uploadVerifiedObject(
                key, new ByteArrayInputStream(replacement), "text/markdown", replacement.length, sha256(replacement)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("immutable object already exists with different content");

        assertThat(Files.readAllBytes(service.resolveObjectPath(key))).containsExactly(original);
        assertThat(findUploadTemps()).isEmpty();
    }

    @Test
    void uploadVerifiedObject_whenContentV3ImmutableTargetDiffers_thenKeepsOriginalAndFails() throws Exception {
        byte[] original = "content-v3-original".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "content-v3-replacement".getBytes(StandardCharsets.UTF_8);
        String key = "seed/content-v3/posts/post-" + sha256(original) + ".md";
        service.uploadVerifiedObject(
                key, new ByteArrayInputStream(original), "text/markdown", original.length, sha256(original));

        assertThatThrownBy(() -> service.uploadVerifiedObject(
                key, new ByteArrayInputStream(replacement), "text/markdown", replacement.length, sha256(replacement)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("immutable object already exists with different content");

        assertThat(Files.readAllBytes(service.resolveObjectPath(key))).containsExactly(original);
        assertThat(findUploadTemps()).isEmpty();
    }

    @Test
    void uploadVerifiedObject_whenContentV12ImmutableTargetDiffers_thenKeepsOriginalAndFails() throws Exception {
        byte[] original = "content-v12-original".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "content-v12-replacement".getBytes(StandardCharsets.UTF_8);
        String key = "seed/content-v12/posts/post-" + sha256(original) + ".md";
        service.uploadVerifiedObject(
                key, new ByteArrayInputStream(original), "text/markdown", original.length, sha256(original));

        assertThatThrownBy(() -> service.uploadVerifiedObject(
                key, new ByteArrayInputStream(replacement), "text/markdown", replacement.length, sha256(replacement)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("immutable object already exists with different content");

        assertThat(Files.readAllBytes(service.resolveObjectPath(key))).containsExactly(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"content-beta", "content-v0"})
    void uploadVerifiedObject_whenVersionIsNotAccepted_thenUsesNormalReplaceBehavior(String version) throws Exception {
        byte[] original = "mutable-original".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "mutable-replacement".getBytes(StandardCharsets.UTF_8);
        String key = "seed/" + version + "/posts/post.md";

        service.uploadVerifiedObject(
                key, new ByteArrayInputStream(original), "text/markdown", original.length, sha256(original));
        service.uploadVerifiedObject(
                key, new ByteArrayInputStream(replacement), "text/markdown", replacement.length, sha256(replacement));

        assertThat(Files.readAllBytes(service.resolveObjectPath(key))).containsExactly(replacement);
    }

    @Test
    void uploadVerifiedObject_whenImmutableTargetMatches_thenIsIdempotent() throws Exception {
        byte[] data = "same-content".getBytes(StandardCharsets.UTF_8);
        String key = "seed/content-v2/posts/post-" + sha256(data) + ".md";

        service.uploadVerifiedObject(key, new ByteArrayInputStream(data), "text/markdown", data.length, sha256(data));
        service.uploadVerifiedObject(key, new ByteArrayInputStream(data), "text/markdown", data.length, sha256(data));

        assertThat(service.objectMatches(key, sha256(data), data.length)).isTrue();
        assertThat(findUploadTemps()).isEmpty();
    }

    @Test
    void uploadVerifiedObject_whenDraftEditTargetDiffers_thenKeepsOriginalAndFails() throws Exception {
        byte[] original = "draft-edit-original".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "draft-edit-replacement".getBytes(StandardCharsets.UTF_8);
        String key = "posts/42/content-edits/" + sha256(original) + ".md";
        service.uploadVerifiedObject(
                key, new ByteArrayInputStream(original), "text/markdown", original.length, sha256(original));

        assertThatThrownBy(() -> service.uploadVerifiedObject(
                key, new ByteArrayInputStream(replacement), "text/markdown", replacement.length, sha256(replacement)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("immutable object already exists with different content");

        assertThat(Files.readAllBytes(service.resolveObjectPath(key))).containsExactly(original);
        assertThat(findUploadTemps()).isEmpty();
    }

    @Test
    void uploadVerifiedObject_whenDraftEditWritesRace_thenAllCallsConverge() throws Exception {
        byte[] data = "concurrent-draft-edit".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(data);
        String key = "posts/42/content-edits/" + digest + ".md";
        int writers = 8;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(writers)) {
            var futures = java.util.stream.IntStream.range(0, writers)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        service.uploadVerifiedObject(
                                key, new ByteArrayInputStream(data), "text/markdown", data.length, digest);
                        return null;
                    }))
                    .toList();
            ready.await();
            start.countDown();
            for (var future : futures) {
                future.get();
            }
        }

        assertThat(service.objectMatches(key, digest, data.length)).isTrue();
        assertThat(findUploadTemps()).isEmpty();
    }

    @Test
    void uploadVerifiedObject_whenInputFailsMidStream_thenLeavesNoTargetOrTemp() {
        byte[] data = "partial-data".getBytes(StandardCharsets.UTF_8);
        String key = "seed/content-v2/posts/post-" + sha256(data) + ".md";
        InputStream failing = new InputStream() {
            private int index;

            @Override
            public int read() throws IOException {
                if (index == 4) {
                    throw new IOException("source failed");
                }
                return index < data.length ? data[index++] : -1;
            }
        };

        assertThatThrownBy(() -> service.uploadVerifiedObject(
                key, failing, "text/markdown", data.length, sha256(data)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("source failed");

        assertThat(service.objectExists(key)).isFalse();
        assertThat(findUploadTemps()).isEmpty();
    }

    @Test
    void uploadVerifiedObject_rejectsSymlinkPathComponentOutsideStorageRoot() throws Exception {
        Path link = tempDir.resolve("seed");
        try {
            Files.createSymbolicLink(link, outsideDir);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(createWindowsJunction(link, outsideDir),
                    "symbolic links are unavailable: " + exception.getMessage());
        }
        byte[] data = "safe".getBytes(StandardCharsets.UTF_8);
        String key = "seed/content-v2/posts/post-" + sha256(data) + ".md";

        assertThatThrownBy(() -> service.uploadVerifiedObject(
                key, new ByteArrayInputStream(data), "text/markdown", data.length, sha256(data)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storage path");
        assertThat(Files.exists(outsideDir.resolve("content-v2/posts"))).isFalse();
    }

    private boolean createWindowsJunction(Path link, Path target) {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(
                    "cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private java.util.List<Path> findUploadTemps() {
        try (var files = Files.walk(tempDir)) {
            return files.filter(path -> path.getFileName().toString().startsWith(".upload-"))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
