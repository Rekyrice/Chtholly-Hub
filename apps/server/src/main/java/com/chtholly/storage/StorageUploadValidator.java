package com.chtholly.storage;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates application-mediated uploads against their object-key namespace. */
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
            "text/plain",
            "application/json");

    private static final Map<String, String> POST_CONTENT_EXTENSIONS = Map.of(
            "text/markdown", ".md",
            "text/plain", ".txt",
            "application/json", ".json");

    private static final Map<String, String> POST_IMAGE_EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "image/gif", ".gif");

    private static final Pattern AVATAR_KEY = Pattern.compile(
            "avatars/[1-9][0-9]*/[0-9]{8}/[0-9a-fA-F-]{36}\\.(?:jpg|png|webp|gif)");
    private static final Pattern POST_CONTENT_KEY = Pattern.compile(
            "posts/[1-9][0-9]*/content-uploads/[0-9a-f]{32}\\.(?:md|txt|json)");
    private static final Pattern POST_IMAGE_KEY = Pattern.compile(
            "posts/[1-9][0-9]*/images/[0-9]{8}/[0-9a-f]{8}\\.(?:jpg|png|webp|gif)");

    private StorageUploadValidator() {
    }

    /**
     * Validates upload size, media type, and image signature for the object-key namespace.
     *
     * @param objectKey normalized storage object key
     * @param content upload content to validate
     * @throws BusinessException when the key namespace or content violates upload policy
     */
    public static void validate(String objectKey, UploadContent content) {
        if (content == null || content.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传文件不能为空");
        }
        String contentType = normalizeContentType(content.contentType());
        if (isAvatarKey(objectKey)) {
            validateAvatarUpload(objectKey, content, contentType);
        } else if (isPostContentKey(objectKey)) {
            validatePostContentUpload(objectKey, content, contentType);
        } else if (isPostImageKey(objectKey)) {
            validatePostImageUpload(objectKey, content, contentType);
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的上传路径");
        }
    }

    /** Returns the canonical suffix for one safe post-content media type. */
    public static String extensionForPostContentType(String contentType) {
        String normalized = normalizeContentType(contentType);
        String extension = normalized == null
                ? null
                : POST_CONTENT_EXTENSIONS.get(normalized);
        if (extension == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的正文类型");
        }
        return extension;
    }

    /** Returns the canonical suffix for one safe post-image media type. */
    public static String extensionForPostImageType(String contentType) {
        String normalized = normalizeContentType(contentType);
        String extension = normalized == null
                ? null
                : POST_IMAGE_EXTENSIONS.get(normalized);
        if (extension == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的图片类型");
        }
        return extension;
    }

    private static void validateAvatarUpload(
            String objectKey,
            UploadContent content,
            String contentType) {
        if (content.size() > MAX_AVATAR_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像不能超过 10MB");
        }
        assertAllowedImageType(contentType);
        assertExtensionMatches(objectKey, extensionForPostImageType(contentType));
        ImageUploadValidator.validateMagicBytes(content, contentType);
    }

    private static void validatePostImageUpload(
            String objectKey,
            UploadContent content,
            String contentType) {
        if (content.size() > MAX_POST_CONTENT_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图片不能超过 32MB");
        }
        assertAllowedImageType(contentType);
        assertExtensionMatches(objectKey, extensionForPostImageType(contentType));
        ImageUploadValidator.validateMagicBytes(content, contentType);
    }

    private static void validatePostContentUpload(
            String objectKey,
            UploadContent content,
            String contentType) {
        if (content.size() > MAX_POST_CONTENT_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文不能超过 32MB");
        }
        if (contentType == null || !ALLOWED_POST_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的正文类型");
        }
        assertExtensionMatches(objectKey, extensionForPostContentType(contentType));
    }

    private static boolean isAvatarKey(String objectKey) {
        return AVATAR_KEY.matcher(objectKey).matches();
    }

    private static boolean isPostContentKey(String objectKey) {
        return POST_CONTENT_KEY.matcher(objectKey).matches();
    }

    private static boolean isPostImageKey(String objectKey) {
        return POST_IMAGE_KEY.matcher(objectKey).matches();
    }

    private static void assertAllowedImageType(String contentType) {
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的图片类型");
        }
    }

    private static void assertExtensionMatches(String objectKey, String expectedExtension) {
        if (!objectKey.endsWith(expectedExtension)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件扩展名与类型不匹配");
        }
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        return contentType.trim().toLowerCase(Locale.ROOT);
    }
}
