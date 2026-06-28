package com.chtholly.storage;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;

/**
 * 本地上传端点校验：按 objectKey 场景限制大小与 Content-Type。
 */
public final class StorageUploadValidator {

    public static final long MAX_AVATAR_BYTES = 10L * 1024 * 1024;
    public static final long MAX_POST_CONTENT_BYTES = 32L * 1024 * 1024;

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif");

    private static final Set<String> ALLOWED_POST_CONTENT_TYPES = Set.of(
            "text/markdown",
            "text/html",
            "text/plain",
            "application/json");

    private StorageUploadValidator() {
    }

    public static void validate(String objectKey, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传文件不能为空");
        }
        String contentType = normalizeContentType(file.getContentType());
        if (isAvatarKey(objectKey)) {
            validateAvatarUpload(file, contentType);
        } else if (isPostContentKey(objectKey)) {
            validatePostContentUpload(file, contentType);
        } else if (isPostImageKey(objectKey)) {
            validatePostImageUpload(file, contentType);
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的上传路径");
        }
    }

    private static void validateAvatarUpload(MultipartFile file, String contentType) {
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像不能超过 10MB");
        }
        assertAllowedImageType(contentType);
        ImageUploadValidator.validateMagicBytes(file, contentType);
    }

    private static void validatePostImageUpload(MultipartFile file, String contentType) {
        if (file.getSize() > MAX_POST_CONTENT_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图片不能超过 32MB");
        }
        assertAllowedImageType(contentType);
        ImageUploadValidator.validateMagicBytes(file, contentType);
    }

    private static void validatePostContentUpload(MultipartFile file, String contentType) {
        if (file.getSize() > MAX_POST_CONTENT_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文不能超过 32MB");
        }
        if (contentType == null || !ALLOWED_POST_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的正文类型");
        }
    }

    private static boolean isAvatarKey(String objectKey) {
        return objectKey.startsWith("avatars/");
    }

    private static boolean isPostContentKey(String objectKey) {
        return objectKey.startsWith("posts/") && objectKey.contains("/content");
    }

    private static boolean isPostImageKey(String objectKey) {
        return objectKey.startsWith("posts/") && objectKey.contains("/images/");
    }

    private static void assertAllowedImageType(String contentType) {
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的图片类型");
        }
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        return contentType.trim().toLowerCase(Locale.ROOT);
    }
}
