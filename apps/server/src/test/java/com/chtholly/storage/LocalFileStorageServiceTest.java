package com.chtholly.storage;

import com.chtholly.storage.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFileStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalFileStorageService service;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.getLocal().setBasePath(tempDir.toString());
        props.getLocal().setPublicUrlPrefix("/uploads");
        service = new LocalFileStorageService(props);
        service.init();
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
        service.uploadObject(key, new ByteArrayInputStream(data), "text/markdown", data.length);

        Path target = service.resolveObjectPath(key);
        assertThat(Files.readString(target)).isEqualTo("# hello");

        service.deleteObject(key);
        assertThat(Files.exists(target)).isFalse();
    }
}
