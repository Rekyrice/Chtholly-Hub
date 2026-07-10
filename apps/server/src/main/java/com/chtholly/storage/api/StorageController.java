package com.chtholly.storage.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.common.ratelimit.RateLimit;
import com.chtholly.common.ratelimit.RateLimitDimension;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.storage.ImageUploadValidator;
import com.chtholly.storage.PresignedUrl;
import com.chtholly.storage.StorageObjectKeyValidator;
import com.chtholly.storage.StorageService;
import com.chtholly.storage.StorageUploadValidator;
import com.chtholly.storage.api.dto.StoragePresignRequest;
import com.chtholly.storage.api.dto.StoragePresignResponse;
import com.chtholly.storage.api.dto.StorageUploadResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.DigestUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 存储直传：OSS 预签名 PUT 或本地 multipart 上传。
 */
@RestController
@RequestMapping("/api/v1/storage")
@Validated
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;
    private final JwtService jwtService;
    private final PostMapper postMapper;

    @RateLimit(key = "storage:presign", maxRequests = 20, windowSeconds = 60, dimension = RateLimitDimension.USER)
    @PostMapping("/presign")
    public StoragePresignResponse presign(@Valid @RequestBody StoragePresignRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);

        long postId;
        try {
            postId = Long.parseLong(request.postId());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "postId 非法");
        }

        Post post = postMapper.findById(postId);
        if (post == null || post.getCreatorId() == null || post.getCreatorId() != userId) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        String scene = request.scene();
        if ("post_image".equals(scene)) {
            ImageUploadValidator.validateImageContentType(request.contentType());
        }
        String objectKey;
        String ext = normalizeExt(request.ext(), request.contentType(), scene);

        if ("post_content".equals(scene)) {
            objectKey = "posts/" + postId + "/content" + ext;
        } else if ("post_image".equals(scene)) {
            String date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("UTC")).format(Instant.now());
            String rand = UUID.randomUUID().toString().replaceAll("-", "").substring(0, 8);
            objectKey = "posts/" + postId + "/images/" + date + "/" + rand + ext;
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的上传场景");
        }

        PresignedUrl presigned = storageService.generatePresignedPutUrl(objectKey, request.contentType());
        return new StoragePresignResponse(
                objectKey,
                presigned.url(),
                presigned.headers(),
                presigned.expiresInSeconds(),
                presigned.method(),
                storageService.resolvePublicUrl(objectKey));
    }

    /**
     * 本地存储模式下的 multipart 上传端点（OSS 模式不应调用）。
     */
    @RateLimit(key = "storage:upload", maxRequests = 30, windowSeconds = 60, dimension = RateLimitDimension.USER)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StorageUploadResponse upload(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam("objectKey") String objectKey,
                                        @RequestParam("file") MultipartFile file) {
        long userId = jwtService.extractUserId(jwt);
        StorageObjectKeyValidator.assertSafeObjectKey(objectKey);
        verifyUploadPermission(userId, objectKey);
        StorageUploadValidator.validate(objectKey, file);

        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件读取失败");
        }

        try {
            storageService.uploadObject(
                    objectKey,
                    new ByteArrayInputStream(data),
                    file.getContentType(),
                    data.length);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件写入失败");
        }

        String etag = DigestUtils.md5DigestAsHex(data);
        return new StorageUploadResponse(etag);
    }

    private void verifyUploadPermission(long userId, String objectKey) {
        if (!objectKey.startsWith("posts/")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的上传路径");
        }
        String[] parts = objectKey.split("/");
        if (parts.length < 2) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "objectKey 非法");
        }
        long postId;
        try {
            postId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "objectKey 非法");
        }
        Post post = postMapper.findById(postId);
        if (post == null || post.getCreatorId() == null || post.getCreatorId() != userId) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
    }

    private String normalizeExt(String ext, String contentType, String scene) {
        if (ext != null && !ext.isBlank()) {
            return ext.startsWith(".") ? ext : "." + ext;
        }
        if ("post_content".equals(scene)) {
            return switch (contentType) {
                case "text/markdown" -> ".md";
                case "text/html" -> ".html";
                case "text/plain" -> ".txt";
                case "application/json" -> ".json";
                default -> ".bin";
            };
        } else {
            return switch (contentType) {
                case "image/jpeg" -> ".jpg";
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                case "image/gif" -> ".gif";
                default -> ".img";
            };
        }
    }
}
