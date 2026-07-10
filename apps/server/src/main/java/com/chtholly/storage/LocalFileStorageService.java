package com.chtholly.storage;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.storage.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 本地文件系统存储（默认）：无需 OSS 即可上传头像与发帖内容。
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements StorageService {

    private static final int PRESIGN_EXPIRES_SECONDS = 600;
    private static final String UPLOAD_ENDPOINT = "/api/v1/storage/upload";

    private final StorageProperties props;
    private Path basePath;

    @PostConstruct
    void init() {
        basePath = Paths.get(props.getLocal().getBasePath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地存储目录: " + basePath, e);
        }
    }

    @Override
    public String uploadAvatar(long userId, InputStream inputStream, String contentType) throws IOException {
        String normalizedType = normalizeContentType(contentType);
        String ext = ImageUploadValidator.extensionForContentType(normalizedType);
        String date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        String objectKey = "avatars/" + userId + "/" + date + "/" + UUID.randomUUID() + ext;
        writeObject(objectKey, inputStream);
        return publicUrl(objectKey);
    }

    @Override
    public PresignedUrl generatePresignedPutUrl(String objectKey, String contentType) {
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        Map<String, String> headers = contentType != null && !contentType.isBlank()
                ? Map.of("Content-Type", contentType.trim().toLowerCase())
                : Map.of();
        return new PresignedUrl(UPLOAD_ENDPOINT, headers, PRESIGN_EXPIRES_SECONDS, "POST");
    }

    @Override
    public void uploadObject(String objectKey, InputStream inputStream, String contentType, long size)
            throws IOException {
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        writeObject(objectKey, inputStream);
    }

    @Override
    public void deleteObject(String objectKey) {
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        Path target = resolveObjectPath(objectKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "删除文件失败");
        }
    }

    Path resolveObjectPath(String objectKey) {
        Path target = basePath.resolve(objectKey).normalize();
        if (!target.startsWith(basePath)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "objectKey 非法");
        }
        return target;
    }

    private void writeObject(String objectKey, InputStream inputStream) throws IOException {
        Path target = resolveObjectPath(objectKey);
        Files.createDirectories(target.getParent());
        Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private String publicUrl(String objectKey) {
        String prefix = props.getLocal().getPublicUrlPrefix().replaceAll("/$", "");
        return prefix + "/" + objectKey;
    }

    @Override
    public String resolvePublicUrl(String objectKey) {
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        return publicUrl(objectKey);
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Content-Type 不能为空");
        }
        return contentType.trim().toLowerCase();
    }
}
