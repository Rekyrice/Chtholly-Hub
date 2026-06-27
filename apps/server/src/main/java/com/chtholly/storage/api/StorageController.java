package com.chtholly.storage.api;

import com.chtholly.common.ratelimit.RateLimit;
import com.chtholly.common.ratelimit.RateLimitDimension;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.auth.token.JwtService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.storage.OssStorageService;
import com.chtholly.storage.api.dto.StoragePresignRequest;
import com.chtholly.storage.api.dto.StoragePresignResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for OSS presigned uploads (post content and inline images).
 */
@RestController
@RequestMapping("/api/v1/storage")
@Validated
@RequiredArgsConstructor
public class StorageController {

    private final OssStorageService ossStorageService;
    private final JwtService jwtService;
    private final PostMapper postMapper;

    /**
     * Issues a presigned PUT URL for direct client-to-OSS upload.
     *
     * @param request upload scene, content type, and target post metadata
     * @param jwt authenticated user JWT
     * @return presigned URL, object key, required headers, and expiry seconds
     */
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

        // 权限校验：postId 必须属于当前用户
        Post post = postMapper.findById(postId);
        if (post == null || post.getCreatorId() == null || post.getCreatorId() != userId) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        String scene = request.scene();
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

        int expiresIn = 600; // 10 分钟
        String putUrl = ossStorageService.generatePresignedPutUrl(objectKey, request.contentType(), expiresIn);
        Map<String, String> headers = Map.of("Content-Type", request.contentType());
        return new StoragePresignResponse(objectKey, putUrl, headers, expiresIn);
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
                default -> ".img";
            };
        }
    }
}
