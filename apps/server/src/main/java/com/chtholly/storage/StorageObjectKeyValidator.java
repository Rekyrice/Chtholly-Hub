package com.chtholly.storage;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * objectKey 安全校验，防止路径遍历。
 */
public final class StorageObjectKeyValidator {

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
}
