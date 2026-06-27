package com.chtholly.storage;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

/**
 * 图片上传校验：大小、Content-Type 白名单与 magic bytes 一致性检查。
 */
public final class ImageUploadValidator {

    public static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif");

    private ImageUploadValidator() {
    }

    /**
     * 校验头像 multipart 文件。
     */
    public static void validateAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像文件不能为空");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像不能超过 5MB");
        }
        String contentType = normalizeContentType(file.getContentType());
        assertAllowedImageType(contentType);
        validateMagicBytes(file, contentType);
    }

    /**
     * 校验预签名请求的 Content-Type 是否在图片白名单内。
     */
    public static void validateImageContentType(String contentType) {
        assertAllowedImageType(normalizeContentType(contentType));
    }

    /**
     * 根据 Content-Type 返回文件扩展名（带点）。
     */
    public static String extensionForContentType(String contentType) {
        return switch (normalizeContentType(contentType)) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".img";
        };
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

    private static void validateMagicBytes(MultipartFile file, String contentType) {
        byte[] header = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.read(header);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像文件读取失败");
        }
        if (read < 3 || !matchesMagic(header, read, contentType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件内容与类型不匹配");
        }
    }

    static boolean matchesMagic(byte[] header, int read, String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> read >= 3
                    && (header[0] & 0xFF) == 0xFF
                    && (header[1] & 0xFF) == 0xD8
                    && (header[2] & 0xFF) == 0xFF;
            case "image/png" -> read >= 8
                    && (header[0] & 0xFF) == 0x89
                    && header[1] == 'P'
                    && header[2] == 'N'
                    && header[3] == 'G'
                    && header[4] == 0x0D
                    && header[5] == 0x0A
                    && header[6] == 0x1A
                    && header[7] == 0x0A;
            case "image/gif" -> read >= 6
                    && header[0] == 'G'
                    && header[1] == 'I'
                    && header[2] == 'F'
                    && header[3] == '8'
                    && (header[4] == '7' || header[4] == '9')
                    && header[5] == 'a';
            case "image/webp" -> read >= 12
                    && header[0] == 'R'
                    && header[1] == 'I'
                    && header[2] == 'F'
                    && header[3] == 'F'
                    && header[8] == 'W'
                    && header[9] == 'E'
                    && header[10] == 'B'
                    && header[11] == 'P';
            default -> false;
        };
    }
}
