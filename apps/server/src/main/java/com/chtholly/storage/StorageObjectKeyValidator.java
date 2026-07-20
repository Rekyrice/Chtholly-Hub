package com.chtholly.storage;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * objectKey 安全校验，防止路径遍历。
 */
public final class StorageObjectKeyValidator {

    private static final Pattern CONTENT_PACK_VERSION = Pattern.compile("content-v[1-9][0-9]*");
    private static final Pattern CONTENT_PACK_OBJECT_KEY =
            Pattern.compile("seed/content-v[1-9][0-9]*/.+");
    private static final Pattern DRAFT_EDIT_OBJECT_KEY =
            Pattern.compile("posts/[1-9][0-9]*/content-edits/[0-9a-f]{64}\\.md");

    private StorageObjectKeyValidator() {
    }

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
                || objectKey != null && DRAFT_EDIT_OBJECT_KEY.matcher(objectKey).matches();
    }
}
