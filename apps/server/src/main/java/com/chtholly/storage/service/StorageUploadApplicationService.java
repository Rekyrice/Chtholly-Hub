package com.chtholly.storage.service;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.storage.ImageUploadValidator;
import com.chtholly.storage.PresignedUrl;
import com.chtholly.storage.StorageObjectKeyValidator;
import com.chtholly.storage.StorageService;
import com.chtholly.storage.StorageUploadValidator;
import com.chtholly.storage.UploadContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Coordinates authorization, object-key construction, validation, and persistence for post uploads.
 */
@Service
public class StorageUploadApplicationService {

    private static final Logger log = LoggerFactory.getLogger(StorageUploadApplicationService.class);
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("UTC"));

    private final StorageService storageService;
    private final PostOwnershipReader postOwnershipReader;
    private final Clock clock;
    private final Supplier<String> randomKeySegmentSupplier;

    /**
     * Creates the production upload use case with UTC time and UUID object-key entropy.
     *
     * @param storageService object storage boundary
     * @param postOwnershipReader post ownership read port
     */
    public StorageUploadApplicationService(
            StorageService storageService,
            PostOwnershipReader postOwnershipReader) {
        this(
                storageService,
                postOwnershipReader,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    }

    StorageUploadApplicationService(
            StorageService storageService,
            PostOwnershipReader postOwnershipReader,
            Clock clock,
            Supplier<String> randomKeySegmentSupplier) {
        this.storageService = storageService;
        this.postOwnershipReader = postOwnershipReader;
        this.clock = clock;
        this.randomKeySegmentSupplier = randomKeySegmentSupplier;
    }

    /**
     * Authorizes a post-scoped upload and creates its presigned storage contract.
     *
     * @param userId authenticated user ID
     * @param rawPostId post ID as received from the precision-safe HTTP payload
     * @param scene supported upload scene
     * @param contentType declared content type
     * @param extension optional file extension
     * @return presigned upload result
     */
    public PresignResult presign(
            long userId,
            String rawPostId,
            String scene,
            String contentType,
            String extension) {
        long postId = parsePostId(rawPostId);
        requireOwnership(postId, userId);

        if ("post_image".equals(scene)) {
            ImageUploadValidator.validateImageContentType(contentType);
        }
        String objectKey = buildObjectKey(postId, scene, contentType, extension);
        PresignedUrl presigned = storageService.generatePresignedPutUrl(objectKey, contentType);
        return new PresignResult(
                objectKey,
                presigned.url(),
                presigned.headers(),
                presigned.expiresInSeconds(),
                presigned.method(),
                storageService.resolvePublicUrl(objectKey));
    }

    /**
     * Authorizes, validates, and persists a local multipart upload.
     *
     * @param userId authenticated user ID
     * @param objectKey requested post-scoped object key
     * @param content transport-neutral, lazily readable upload content
     * @return MD5 entity tag of the persisted bytes
     */
    public String upload(long userId, String objectKey, UploadContent content) {
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        long postId = parsePostIdFromObjectKey(objectKey);
        requireOwnership(postId, userId);
        StorageUploadValidator.validate(objectKey, content);

        byte[] data = readBytes(content);
        try {
            storageService.uploadObject(
                    objectKey,
                    new ByteArrayInputStream(data),
                    content.contentType(),
                    data.length);
        } catch (IOException failure) {
            log.warn("Storage upload failed, objectKey={}: {}", objectKey, failure.getMessage(), failure);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件写入失败");
        }
        return DigestUtils.md5DigestAsHex(data);
    }

    private String buildObjectKey(long postId, String scene, String contentType, String extension) {
        String normalizedExtension = normalizeExtension(extension, contentType, scene);
        if ("post_content".equals(scene)) {
            return "posts/" + postId + "/content" + normalizedExtension;
        }
        if ("post_image".equals(scene)) {
            String date = DATE_FORMATTER.format(Instant.now(clock));
            return "posts/" + postId + "/images/" + date + "/"
                    + randomKeySegmentSupplier.get() + normalizedExtension;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的上传场景");
    }

    private void requireOwnership(long postId, long userId) {
        if (!postOwnershipReader.isOwnedBy(postId, userId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
    }

    private static long parsePostId(String rawPostId) {
        try {
            return Long.parseLong(rawPostId);
        } catch (NumberFormatException failure) {
            log.debug("Rejected invalid presign postId: {}", rawPostId, failure);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "postId 非法");
        }
    }

    private static long parsePostIdFromObjectKey(String objectKey) {
        if (!objectKey.startsWith("posts/")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的上传路径");
        }
        String[] parts = objectKey.split("/");
        if (parts.length < 2) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "objectKey 非法");
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException failure) {
            log.debug("Rejected invalid post segment in objectKey: {}", objectKey, failure);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "objectKey 非法");
        }
    }

    private static byte[] readBytes(UploadContent content) {
        try {
            return content.readAllBytes();
        } catch (IOException failure) {
            log.warn("Failed to read upload content: {}", failure.getMessage(), failure);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件读取失败");
        }
    }

    private static String normalizeExtension(String extension, String contentType, String scene) {
        if (extension != null && !extension.isBlank()) {
            return extension.startsWith(".") ? extension : "." + extension;
        }
        if ("post_content".equals(scene)) {
            return switch (contentType) {
                case "text/markdown" -> ".md";
                case "text/html" -> ".html";
                case "text/plain" -> ".txt";
                case "application/json" -> ".json";
                default -> ".bin";
            };
        }
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".img";
        };
    }

    /**
     * Storage-independent response returned to the HTTP adapter.
     *
     * @param objectKey generated storage object key
     * @param putUrl direct or local upload URL
     * @param headers required request headers
     * @param expiresIn URL lifetime in seconds
     * @param method upload HTTP method
     * @param publicUrl resolved public URL
     */
    public record PresignResult(
            String objectKey,
            String putUrl,
            Map<String, String> headers,
            int expiresIn,
            String method,
            String publicUrl) {
    }
}
