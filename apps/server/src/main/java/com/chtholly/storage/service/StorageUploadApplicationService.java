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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.HexFormat;
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
    @Autowired
    public StorageUploadApplicationService(
            StorageService storageService,
            PostOwnershipReader postOwnershipReader) {
        this(
                storageService,
                postOwnershipReader,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString().replace("-", ""));
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
     * Authorizes a draft-scoped upload and creates its storage contract.
     *
     * @param userId authenticated user ID
     * @param rawPostId post ID as received from the precision-safe HTTP payload
     * @param scene supported upload scene
     * @param contentType declared content type
     * @param extension compatibility metadata; the server derives the authoritative suffix from
     *                  the validated content type
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
        String objectKey = buildObjectKey(postId, scene, contentType);
        PresignedUrl presigned = storageService.createUploadContract(objectKey, contentType);
        return new PresignResult(
                objectKey,
                presigned.url(),
                presigned.headers(),
                presigned.expiresInSeconds(),
                presigned.method(),
                storageService.resolvePublicUrl(objectKey));
    }

    /**
     * Authorizes, validates, and persists an application-mediated multipart upload.
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
            if (StorageObjectKeyValidator.isPostContentUploadObjectKey(objectKey)) {
                storageService.uploadVerifiedObject(
                        objectKey,
                        new ByteArrayInputStream(data),
                        content.contentType(),
                        data.length,
                        sha256(data));
            } else {
                storageService.uploadObject(
                        objectKey,
                        new ByteArrayInputStream(data),
                        content.contentType(),
                        data.length);
            }
        } catch (IOException failure) {
            log.warn("Storage upload failed, objectKey={}: {}", objectKey, failure.getMessage(), failure);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件写入失败");
        }
        return DigestUtils.md5DigestAsHex(data);
    }

    private String buildObjectKey(long postId, String scene, String contentType) {
        if ("post_content".equals(scene)) {
            return "posts/" + postId + "/content-uploads/"
                    + randomKeySegmentSupplier.get()
                    + StorageUploadValidator.extensionForPostContentType(contentType);
        }
        if ("post_image".equals(scene)) {
            String extension = StorageUploadValidator.extensionForPostImageType(contentType);
            String date = DATE_FORMATTER.format(Instant.now(clock));
            return "posts/" + postId + "/images/" + date + "/"
                    + randomKeySegmentSupplier.get().substring(0, 8) + extension;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的上传场景");
    }

    private void requireOwnership(long postId, long userId) {
        if (!postOwnershipReader.isDraftOwnedBy(postId, userId)) {
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

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
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
