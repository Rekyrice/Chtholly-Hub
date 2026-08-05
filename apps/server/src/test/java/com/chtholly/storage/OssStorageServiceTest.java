package com.chtholly.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectRequest;
import com.chtholly.storage.config.OssProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssStorageServiceTest {

    private OSS client;
    private OssStorageService service;

    @BeforeEach
    void setUp() {
        OssProperties props = new OssProperties();
        props.setEndpoint("oss-cn-test.aliyuncs.com");
        props.setAccessKeyId("access-key");
        props.setAccessKeySecret("secret");
        props.setBucket("bucket");
        client = mock(OSS.class);
        service = spy(new OssStorageService(props));
        doReturn(client).when(service).newClient();
    }

    @Test
    void uploadVerifiedObject_writesSha256MetadataAndContentLength() throws Exception {
        byte[] data = "verified-object".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(data);
        String key = "seed/content-v2/posts/post-" + hash + ".md";

        service.uploadVerifiedObject(
                key, new ByteArrayInputStream(data), "text/markdown", data.length, hash);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture());
        assertThat(request.getValue().getMetadata().getContentLength()).isEqualTo(data.length);
        assertThat(request.getValue().getMetadata().getUserMetadata()).containsEntry("sha256", hash);
        assertThat(request.getValue().getMetadata().getRawMetadata())
                .containsEntry("x-oss-forbid-overwrite", "true");
        verify(client).setObjectAcl(
                "bucket", key, com.aliyun.oss.model.CannedAccessControlList.PublicRead);
        verify(client).shutdown();
    }

    @Test
    void uploadVerifiedObject_whenConditionalPutFindsMatchingObject_thenSucceedsIdempotently() throws Exception {
        byte[] data = "same-post-content".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(data);
        String key = "posts/42/content-uploads/" + "a".repeat(32) + ".md";
        OSSException conflict = fileAlreadyExists();
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(conflict);
        ObjectMetadata existing = new ObjectMetadata();
        existing.setContentLength(data.length);
        existing.addUserMetadata("sha256", hash);
        when(client.getObjectMetadata("bucket", key)).thenReturn(existing);

        service.uploadVerifiedObject(
                key, new ByteArrayInputStream(data), "text/markdown", data.length, hash);

        verify(client).setObjectAcl("bucket", key, com.aliyun.oss.model.CannedAccessControlList.PublicRead);
        verify(client).shutdown();
    }

    @Test
    void uploadVerifiedObject_whenConditionalPutFindsDifferentObject_thenFailsWithoutAclChange() throws Exception {
        byte[] data = "replacement-post-content".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(data);
        String key = "posts/42/content-uploads/" + "a".repeat(32) + ".md";
        OSSException conflict = fileAlreadyExists();
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(conflict);
        ObjectMetadata existing = new ObjectMetadata();
        existing.setContentLength(data.length);
        existing.addUserMetadata("sha256", "0".repeat(64));
        when(client.getObjectMetadata("bucket", key)).thenReturn(existing);

        assertThatThrownBy(() -> service.uploadVerifiedObject(
                key, new ByteArrayInputStream(data), "text/markdown", data.length, hash))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("different content");

        verify(client, never()).setObjectAcl(
                eq("bucket"), eq(key), any(com.aliyun.oss.model.CannedAccessControlList.class));
        verify(client).shutdown();
    }

    @Test
    void uploadVerifiedObject_whenPutFailsForAnotherReason_thenDoesNotTreatItAsAnExistingKey() {
        byte[] data = "verified-object".getBytes(StandardCharsets.UTF_8);
        String key = "posts/42/content-uploads/" + "a".repeat(32) + ".md";
        OSSException failure = ossFailure("PreconditionFailed");
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.uploadVerifiedObject(
                key, new ByteArrayInputStream(data), "text/markdown", data.length, sha256(data)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("OSS verified upload failed")
                .hasCause(failure);

        verify(client, never()).getObjectMetadata("bucket", key);
        verify(client, never()).setObjectAcl(
                eq("bucket"), eq(key), any(com.aliyun.oss.model.CannedAccessControlList.class));
        verify(client).shutdown();
    }

    @Test
    void uploadVerifiedObject_whenSdkClientFails_thenWrapsTheFailureAtTheStorageBoundary() {
        byte[] data = "verified-object".getBytes(StandardCharsets.UTF_8);
        String key = "posts/42/content-uploads/" + "a".repeat(32) + ".md";
        ClientException failure = new ClientException("transport unavailable");
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.uploadVerifiedObject(
                key, new ByteArrayInputStream(data), "text/markdown", data.length, sha256(data)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("OSS verified upload failed")
                .hasCause(failure);

        verify(client, never()).getObjectMetadata("bucket", key);
        verify(client, never()).setObjectAcl(
                eq("bucket"), eq(key), any(com.aliyun.oss.model.CannedAccessControlList.class));
        verify(client).shutdown();
    }

    @Test
    void uploadObject_whenSdkClientFails_thenWrapsTheFailureAtTheStorageBoundary() {
        byte[] data = "image-object".getBytes(StandardCharsets.UTF_8);
        String key = "posts/42/images/20260805/deadbeef.png";
        ClientException failure = new ClientException("transport unavailable");
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.uploadObject(
                key, new ByteArrayInputStream(data), "image/png", data.length))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("OSS upload failed")
                .hasCause(failure);

        verify(client, never()).setObjectAcl(
                eq("bucket"), eq(key), any(com.aliyun.oss.model.CannedAccessControlList.class));
        verify(client).shutdown();
    }

    @Test
    void uploadObject_whenKeyRequiresImmutableSemantics_thenRejectsUnverifiedWrite() {
        byte[] data = "unverified-object".getBytes(StandardCharsets.UTF_8);
        String key = "posts/42/content-uploads/" + "a".repeat(32) + ".md";

        assertThatThrownBy(() -> service.uploadObject(
                key, new ByteArrayInputStream(data), "text/markdown", data.length))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("verified upload");

        verify(client, never()).putObject(any(PutObjectRequest.class));
        verify(client, never()).setObjectAcl(
                eq("bucket"), eq(key), any(com.aliyun.oss.model.CannedAccessControlList.class));
    }

    @Test
    void createUploadContract_routesThroughValidatedApplicationUploadEndpoint() {
        PresignedUrl upload = service.createUploadContract(
                "posts/42/images/20260805/deadbeef.png", "image/png");

        assertThat(upload.url()).isEqualTo("/api/v1/storage/upload");
        assertThat(upload.method()).isEqualTo("POST");
        assertThat(upload.headers()).containsEntry("Content-Type", "image/png");
        verify(client, never()).generatePresignedUrl(any());
    }

    @Test
    void uploadVerifiedObject_rejectsWrongDigestBeforePut() {
        byte[] data = "verified-object".getBytes(StandardCharsets.UTF_8);
        String key = "seed/content-v2/posts/post-" + "0".repeat(64) + ".md";

        assertThatThrownBy(() -> service.uploadVerifiedObject(
                key, new ByteArrayInputStream(data), "text/markdown", data.length, "0".repeat(64)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("sha256 mismatch");

        verify(client, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void objectMatches_usesVerifiedMetadataWithoutDownloading() throws Exception {
        byte[] data = "verified-object".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(data);
        String key = "seed/content-v2/posts/post-" + hash + ".md";
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(data.length);
        metadata.addUserMetadata("sha256", hash);
        when(client.getObjectMetadata("bucket", key)).thenReturn(metadata);

        assertThat(service.objectMatches(key, hash, data.length)).isTrue();

        verify(client, never()).getObject(any(String.class), any(String.class));
        verify(client).shutdown();
    }

    @Test
    void objectMatches_whenLegacyMetadataMissing_thenDownloadsAndHashesObject() throws Exception {
        byte[] data = "legacy-object".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(data);
        String key = "seed/content-v2/posts/post-" + hash + ".md";
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(data.length);
        when(client.getObjectMetadata("bucket", key)).thenReturn(metadata);
        OSSObject object = new OSSObject();
        object.setObjectContent(new ByteArrayInputStream(data));
        when(client.getObject("bucket", key)).thenReturn(object);

        assertThat(service.objectMatches(key, hash, data.length)).isTrue();

        verify(client).getObject("bucket", key);
        verify(client).shutdown();
    }

    @Test
    void objectMatches_whenObjectDoesNotExist_thenReturnsFalse() throws Exception {
        String key = "posts/42/content-uploads/" + "a".repeat(32) + ".md";
        when(client.getObjectMetadata("bucket", key)).thenThrow(ossFailure("NoSuchKey"));

        assertThat(service.objectMatches(key, "b".repeat(64), 5L)).isFalse();

        verify(client).shutdown();
    }

    @Test
    void objectMatches_whenSdkServiceFails_thenWrapsTheFailureAtTheStorageBoundary() {
        String key = "posts/42/content-uploads/" + "a".repeat(32) + ".md";
        OSSException failure = ossFailure("AccessDenied");
        when(client.getObjectMetadata("bucket", key)).thenThrow(failure);

        assertThatThrownBy(() -> service.objectMatches(key, "b".repeat(64), 5L))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("OSS object verification failed")
                .hasCause(failure);

        verify(client).shutdown();
    }

    @Test
    void objectMatches_whenSdkClientFails_thenWrapsTheFailureAtTheStorageBoundary() {
        String key = "posts/42/content-uploads/" + "a".repeat(32) + ".md";
        ClientException failure = new ClientException("transport unavailable");
        when(client.getObjectMetadata("bucket", key)).thenThrow(failure);

        assertThatThrownBy(() -> service.objectMatches(key, "b".repeat(64), 5L))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("OSS object verification failed")
                .hasCause(failure);

        verify(client).shutdown();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static OSSException fileAlreadyExists() {
        return ossFailure("FileAlreadyExists");
    }

    private static OSSException ossFailure(String errorCode) {
        return new OSSException(
                "OSS request failed",
                errorCode,
                "request-id",
                "host-id",
                null,
                null,
                null);
    }
}
