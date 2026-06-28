package com.chtholly.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.PutObjectRequest;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.storage.config.OssProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.type", havingValue = "oss")
public class OssStorageService implements StorageService {

    private static final int PRESIGN_EXPIRES_SECONDS = 600;

    private final OssProperties props;

    @Override
    public String uploadAvatar(long userId, InputStream inputStream, String contentType) throws IOException {
        ensureConfigured();
        String normalizedType = contentType == null ? null : contentType.trim().toLowerCase();
        String ext = ImageUploadValidator.extensionForContentType(normalizedType);
        String date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        String objectKey = props.getFolder() + "/" + userId + "/" + date + "/" + UUID.randomUUID() + ext;

        OSS client = newClient();
        try {
            PutObjectRequest request = new PutObjectRequest(props.getBucket(), objectKey, inputStream);
            client.putObject(request);
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
        throw new BusinessException(ErrorCode.BAD_REQUEST, "OSS 模式请使用预签名直传");
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

    private String publicUrl(String objectKey) {
        if (props.getPublicDomain() != null && !props.getPublicDomain().isBlank()) {
            return props.getPublicDomain().replaceAll("/$", "") + "/" + objectKey;
        }
        return "https://" + props.getBucket() + "." + props.getEndpoint() + "/" + objectKey;
    }

    private OSS newClient() {
        return new OSSClientBuilder().build(props.getEndpoint(), props.getAccessKeyId(), props.getAccessKeySecret());
    }

    private void ensureConfigured() {
        if (props.getEndpoint() == null || props.getAccessKeyId() == null
                || props.getAccessKeySecret() == null || props.getBucket() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "对象存储未配置");
        }
    }
}
