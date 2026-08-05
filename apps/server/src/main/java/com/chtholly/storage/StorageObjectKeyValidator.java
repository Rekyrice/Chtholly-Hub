package com.chtholly.storage;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/** Validates storage object keys and their ownership-scoped namespaces. */
public final class StorageObjectKeyValidator {

    private static final Pattern CONTENT_PACK_VERSION = Pattern.compile("content-v[1-9][0-9]*");
    private static final Pattern CONTENT_PACK_OBJECT_KEY =
            Pattern.compile("seed/content-v[1-9][0-9]*/.+");
    private static final Pattern DRAFT_EDIT_OBJECT_KEY =
            Pattern.compile("posts/[1-9][0-9]*/content-edits/[0-9a-f]{64}\\.md");
    private static final Pattern POST_CONTENT_UPLOAD_OBJECT_KEY =
            Pattern.compile("posts/([1-9][0-9]*)/content-uploads/[0-9a-f]{32}\\.(?:md|txt|json)");
    private static final Pattern LEGACY_POST_CONTENT_OBJECT_KEY =
            Pattern.compile("posts/([1-9][0-9]*)/content\\.(?:md|txt|json)");

    private StorageObjectKeyValidator() {
    }

    /**
     * Rejects blank, absolute, traversing, or platform-dependent object keys.
     *
     * @param objectKey storage object key
     * @throws BusinessException when the key is unsafe
     */
    public static void assertSafeObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "objectKey 不能为空");
        }
        if (objectKey.startsWith("/") || objectKey.contains("..") || objectKey.contains("\\")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "objectKey 非法");
        }
        Path normalized = Paths.get(objectKey).normalize();
        String normalizedStr = normalized.toString().replace('\\', '/');
        if (normalizedStr.startsWith("..") || normalizedStr.contains("/../")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "objectKey 非法");
        }
    }

    /**
     * Requires a new immutable or historical post-content key to belong to one post.
     *
     * @param objectKey content object key
     * @param postId post receiving the content binding
     * @throws BusinessException when the key is unsupported or belongs to another post
     */
    public static void assertPostContentObjectKeyBelongsToPost(String objectKey, long postId) {
        assertSafeObjectKey(objectKey);
        java.util.regex.Matcher upload = POST_CONTENT_UPLOAD_OBJECT_KEY.matcher(objectKey);
        java.util.regex.Matcher legacy = LEGACY_POST_CONTENT_OBJECT_KEY.matcher(objectKey);
        String ownerSegment;
        if (upload.matches()) {
            ownerSegment = upload.group(1);
        } else if (legacy.matches()) {
            ownerSegment = legacy.group(1);
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的正文对象路径");
        }
        if (!ownerSegment.equals(Long.toString(postId))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文对象不属于该文章");
        }
    }

    /**
     * Identifies a canonical immutable post-content upload key.
     *
     * @param objectKey storage object key
     * @return whether the key uses the post-content upload namespace and random segment
     */
    public static boolean isPostContentUploadObjectKey(String objectKey) {
        return objectKey != null && POST_CONTENT_UPLOAD_OBJECT_KEY.matcher(objectKey).matches();
    }

    /**
     * Validates and returns a canonical content-pack version.
     *
     * @param version manifest version
     * @return the validated version
     */
    public static String requireContentPackVersion(String version) {
        if (version == null || !CONTENT_PACK_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("invalid content pack version: " + version);
        }
        return version;
    }

    /**
     * Builds the only object-key namespace accepted for a content-pack version.
     *
     * @param version validated manifest version
     * @return storage prefix ending in a slash
     */
    public static String contentPackObjectPrefix(String version) {
        return "seed/" + requireContentPackVersion(version) + "/";
    }

    /**
     * Identifies object keys that must use immutable content-pack installation semantics.
     *
     * @param objectKey safe storage object key
     * @return whether the key belongs to an accepted content-pack version
     */
    public static boolean isContentPackObjectKey(String objectKey) {
        return objectKey != null && CONTENT_PACK_OBJECT_KEY.matcher(objectKey).matches();
    }

    /**
     * Identifies content-addressed object keys that must never replace different bytes.
     *
     * @param objectKey safe storage object key
     * @return whether the key requires immutable installation semantics
     */
    public static boolean isImmutableObjectKey(String objectKey) {
        return isContentPackObjectKey(objectKey)
                || objectKey != null && DRAFT_EDIT_OBJECT_KEY.matcher(objectKey).matches()
                || isPostContentUploadObjectKey(objectKey);
    }
}
