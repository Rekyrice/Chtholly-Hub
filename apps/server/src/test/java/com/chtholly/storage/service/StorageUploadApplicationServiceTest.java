package com.chtholly.storage.service;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.storage.PresignedUrl;
import com.chtholly.storage.StorageService;
import com.chtholly.storage.UploadContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StorageUploadApplicationServiceTest {

    @Mock
    private StorageService storageService;
    @Mock
    private PostOwnershipReader postOwnershipReader;

    private StorageUploadApplicationService service;

    @BeforeEach
    void setUp() {
        service = new StorageUploadApplicationService(
                storageService,
                postOwnershipReader,
                Clock.fixed(Instant.parse("2026-08-05T10:15:30Z"), ZoneOffset.UTC),
                () -> "deadbeef");
    }

    @Test
    void presignPostImage_buildsStableObjectKeyAndMapsStorageContract() {
        when(postOwnershipReader.isOwnedBy(42L, 7L)).thenReturn(true);
        when(storageService.generatePresignedPutUrl(
                "posts/42/images/20260805/deadbeef.png", "image/png"))
                .thenReturn(new PresignedUrl("https://put", Map.of("x-test", "1"), 300, "PUT"));
        when(storageService.resolvePublicUrl("posts/42/images/20260805/deadbeef.png"))
                .thenReturn("https://cdn/posts/42/images/20260805/deadbeef.png");

        StorageUploadApplicationService.PresignResult result = service.presign(
                7L, "42", "post_image", "image/png", null);

        assertThat(result.objectKey()).isEqualTo("posts/42/images/20260805/deadbeef.png");
        assertThat(result.putUrl()).isEqualTo("https://put");
        assertThat(result.headers()).containsEntry("x-test", "1");
        assertThat(result.expiresIn()).isEqualTo(300);
        assertThat(result.method()).isEqualTo("PUT");
        assertThat(result.publicUrl()).isEqualTo("https://cdn/posts/42/images/20260805/deadbeef.png");
    }

    @Test
    void presignInvalidPostId_preservesErrorMessageAndSkipsOwnershipLookup() {
        assertThatThrownBy(() -> service.presign(7L, "not-a-number", "post_content", "text/markdown", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("postId 非法"));

        verify(postOwnershipReader, never()).isOwnedBy(anyLong(), anyLong());
    }

    @Test
    void presignForeignDraft_preservesErrorMessage() {
        when(postOwnershipReader.isOwnedBy(42L, 7L)).thenReturn(false);

        assertThatThrownBy(() -> service.presign(7L, "42", "post_content", "text/markdown", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("草稿不存在或无权限"));
    }

    @Test
    void uploadPostContent_validatesOwnershipAndWritesBytes() throws IOException {
        byte[] bytes = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UploadContent content = content("text/markdown", bytes);
        when(postOwnershipReader.isOwnedBy(42L, 7L)).thenReturn(true);

        String etag = service.upload(7L, "posts/42/content.md", content);

        assertThat(etag).isEqualTo("5d41402abc4b2a76b9719d911017c592");
        verify(storageService).uploadObject(
                eq("posts/42/content.md"), any(), eq("text/markdown"), eq(5L));
    }

    @Test
    void uploadChecksOwnershipAndMetadataBeforeReadingPayload() throws IOException {
        UploadContent content = org.mockito.Mockito.mock(UploadContent.class);
        when(content.isEmpty()).thenReturn(false);
        when(content.size()).thenReturn(5L);
        when(content.contentType()).thenReturn("text/markdown");
        when(content.readAllBytes()).thenReturn("hello".getBytes());
        when(postOwnershipReader.isOwnedBy(42L, 7L)).thenReturn(true);

        service.upload(7L, "posts/42/content.md", content);

        org.mockito.InOrder order = inOrder(postOwnershipReader, content, storageService);
        order.verify(postOwnershipReader).isOwnedBy(42L, 7L);
        order.verify(content).isEmpty();
        order.verify(content).contentType();
        order.verify(content).size();
        order.verify(content).readAllBytes();
        order.verify(content).contentType();
        order.verify(storageService).uploadObject(
                eq("posts/42/content.md"), any(), eq("text/markdown"), eq(5L));
    }

    @Test
    void uploadPostImage_rejectsInvalidMagicBeforeReadingFullPayload() throws IOException {
        UploadContent content = org.mockito.Mockito.mock(UploadContent.class);
        when(content.isEmpty()).thenReturn(false);
        when(content.size()).thenReturn(8L);
        when(content.contentType()).thenReturn("image/png");
        when(content.openStream()).thenReturn(new ByteArrayInputStream("MZ-fake!".getBytes()));
        when(postOwnershipReader.isOwnedBy(42L, 7L)).thenReturn(true);

        assertThatThrownBy(() -> service.upload(
                7L,
                "posts/42/images/20260805/deadbeef.png",
                content))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("文件内容与类型不匹配"));

        verify(content, never()).readAllBytes();
        verify(storageService, never()).uploadObject(any(), any(), any(), anyLong());
    }

    @Test
    void uploadUnsupportedPath_preservesErrorMessageAndSkipsOwnershipLookup() throws IOException {
        UploadContent content = org.mockito.Mockito.mock(UploadContent.class);

        assertThatThrownBy(() -> service.upload(7L, "other/42/content.md", content))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("不支持的上传路径"));

        verify(postOwnershipReader, never()).isOwnedBy(anyLong(), anyLong());
        verify(content, never()).readAllBytes();
    }

    @Test
    void uploadReadFailure_preservesErrorMessage() throws IOException {
        UploadContent content = org.mockito.Mockito.mock(UploadContent.class);
        when(content.isEmpty()).thenReturn(false);
        when(content.size()).thenReturn(1L);
        when(content.contentType()).thenReturn("text/markdown");
        when(content.readAllBytes()).thenThrow(new IOException("read"));
        when(postOwnershipReader.isOwnedBy(42L, 7L)).thenReturn(true);

        assertThatThrownBy(() -> service.upload(7L, "posts/42/content.md", content))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("文件读取失败"));
    }

    @Test
    void uploadWriteFailure_preservesErrorMessage() throws IOException {
        UploadContent content = content("text/markdown", "hello".getBytes());
        when(postOwnershipReader.isOwnedBy(42L, 7L)).thenReturn(true);
        org.mockito.Mockito.doThrow(new IOException("write"))
                .when(storageService)
                .uploadObject(eq("posts/42/content.md"), any(), eq("text/markdown"), eq(5L));

        assertThatThrownBy(() -> service.upload(7L, "posts/42/content.md", content))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).isEqualTo("文件写入失败"));
    }

    private static UploadContent content(String contentType, byte[] bytes) {
        return new UploadContent() {
            @Override
            public boolean isEmpty() {
                return bytes.length == 0;
            }

            @Override
            public long size() {
                return bytes.length;
            }

            @Override
            public String contentType() {
                return contentType;
            }

            @Override
            public InputStream openStream() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public byte[] readAllBytes() {
                return bytes.clone();
            }
        };
    }
}
