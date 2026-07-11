package com.chtholly.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectRequest;
import com.chtholly.storage.config.OssProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
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
        verify(client).shutdown();
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

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
