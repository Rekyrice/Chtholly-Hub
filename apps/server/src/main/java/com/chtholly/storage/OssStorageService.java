package com.chtholly.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectRequest;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.storage.config.OssProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Alibaba OSS storage implementation, including verifiable content-addressed uploads.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.type", havingValue = "oss")
public class OssStorageService implements StorageService {

    private static final int PRESIGN_EXPIRES_SECONDS = 600;

    private final OssProperties props;

    @Override
    public String uploadAvatar(long userId, InputStream inputStream, String contentType) throws IOException {
        ensureConfigured();
        String normalizedType = contentType == null ? null : contentType.trim().toLowerCase(Locale.ROOT);
        String ext = ImageUploadValidator.extensionForContentType(normalizedType);
        String date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        String objectKey = props.getFolder() + "/" + userId + "/" + date + "/" + UUID.randomUUID() + ext;

        OSS client = newClient();
        try {
            client.putObject(new PutObjectRequest(props.getBucket(), objectKey, inputStream));
        } finally {
            client.shutdown();
        }
        return publicUrl(objectKey);
    }

    @Override
    public PresignedUrl generatePresignedPutUrl(String objectKey, String contentType) {
        ensureConfigured();
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        OSS client = newClient();
        try {
            Date expiration = new Date(System.currentTimeMillis() + PRESIGN_EXPIRES_SECONDS * 1000L);
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                    props.getBucket(), objectKey, HttpMethod.PUT);
            request.setExpiration(expiration);
            if (contentType != null && !contentType.isBlank()) {
                request.setContentType(contentType);
            }
            URL url = client.generatePresignedUrl(request);
            Map<String, String> headers = contentType != null && !contentType.isBlank()
                    ? Map.of("Content-Type", contentType)
                    : Map.of();
            return new PresignedUrl(url.toString(), headers, PRESIGN_EXPIRES_SECONDS, "PUT");
        } finally {
            client.shutdown();
        }
    }

    @Override
    public void uploadObject(String objectKey, InputStream inputStream, String contentType, long size) {
        uploadObject(objectKey, inputStream, contentType, size, null);
    }

    @Override
    public void uploadVerifiedObject(
            String objectKey,
            InputStream inputStream,
            String contentType,
            long size,
            String sha256) throws IOException {
        requireSha256(sha256);
        byte[] bytes = inputStream.readAllBytes();
        if (bytes.length != size) {
            throw new IOException(
                    "upload size mismatch for " + objectKey + ": expected " + size + ", actual " + bytes.length);
        }
        String actualSha256 = sha256(bytes);
        if (!actualSha256.equalsIgnoreCase(sha256)) {
            throw new IOException("upload sha256 mismatch for " + objectKey);
        }
        uploadObject(objectKey, new ByteArrayInputStream(bytes), contentType, size, actualSha256);
    }

    @Override
    public boolean objectExists(String objectKey) {
        ensureConfigured();
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        OSS client = newClient();
        try {
            return client.doesObjectExist(props.getBucket(), objectKey);
        } finally {
            client.shutdown();
        }
    }

    @Override
    public boolean objectMatches(String objectKey, String sha256, long size) throws IOException {
        ensureConfigured();
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        requireSha256(sha256);
        OSS client = newClient();
        try {
            ObjectMetadata metadata = client.getObjectMetadata(props.getBucket(), objectKey);
            if (metadata.getContentLength() != size) {
                return false;
            }
            Map<String, String> userMetadata = metadata.getUserMetadata();
            String storedSha256 = userMetadata == null ? null : userMetadata.entrySet().stream()
                    .filter(entry -> "sha256".equalsIgnoreCase(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            if (storedSha256 != null && !storedSha256.isBlank()) {
                return storedSha256.equalsIgnoreCase(sha256);
            }

            // 旧对象没有摘要元数据时下载校验，不能仅凭 object key 或 ETag 接受。
            try (OSSObject object = client.getObject(props.getBucket(), objectKey);
                 InputStream input = object.getObjectContent()) {
                FileIdentity identity = digest(input);
                return identity.size() == size && identity.sha256().equalsIgnoreCase(sha256);
            }
        } finally {
            client.shutdown();
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        ensureConfigured();
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        OSS client = newClient();
        try {
            client.deleteObject(props.getBucket(), objectKey);
        } finally {
            client.shutdown();
        }
    }

    @Override
    public String resolvePublicUrl(String objectKey) {
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        return publicUrl(objectKey);
    }

    private void uploadObject(
            String objectKey,
            InputStream inputStream,
            String contentType,
            long size,
            String sha256) {
        ensureConfigured();
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        OSS client = newClient();
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            if (contentType != null && !contentType.isBlank()) {
                metadata.setContentType(contentType.trim());
            }
            if (size >= 0) {
                metadata.setContentLength(size);
            }
            if (sha256 != null) {
                metadata.addUserMetadata("sha256", sha256);
            }
            client.putObject(new PutObjectRequest(props.getBucket(), objectKey, inputStream, metadata));
            client.setObjectAcl(props.getBucket(), objectKey, CannedAccessControlList.PublicRead);
        } finally {
            client.shutdown();
        }
    }

    private String publicUrl(String objectKey) {
        if (props.getPublicDomain() != null && !props.getPublicDomain().isBlank()) {
            return props.getPublicDomain().replaceAll("/$", "") + "/" + objectKey;
        }
        return "https://" + props.getBucket() + "." + props.getEndpoint() + "/" + objectKey;
    }

    OSS newClient() {
        return new OSSClientBuilder().build(props.getEndpoint(), props.getAccessKeyId(), props.getAccessKeySecret());
    }

    private void ensureConfigured() {
        if (props.getEndpoint() == null || props.getAccessKeyId() == null
                || props.getAccessKeySecret() == null || props.getBucket() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "对象存储未配置");
        }
    }

    private FileIdentity digest(InputStream input) throws IOException {
        MessageDigest digest = newDigest();
        long size;
        try (DigestInputStream source = new DigestInputStream(input, digest)) {
            size = source.transferTo(OutputStream.nullOutputStream());
        }
        return new FileIdentity(size, HexFormat.of().formatHex(digest.digest()));
    }

    private String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireSha256(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("sha256 must contain 64 hexadecimal characters");
        }
    }

    private record FileIdentity(long size, String sha256) {
    }
}
