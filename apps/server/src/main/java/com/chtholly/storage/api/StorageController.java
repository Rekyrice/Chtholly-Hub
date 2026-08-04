package com.chtholly.storage.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.common.ratelimit.RateLimit;
import com.chtholly.common.ratelimit.RateLimitDimension;
import com.chtholly.storage.api.dto.StoragePresignRequest;
import com.chtholly.storage.api.dto.StoragePresignResponse;
import com.chtholly.storage.api.dto.StorageUploadResponse;
import com.chtholly.storage.service.StorageUploadApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * HTTP adapter for presigned and local multipart storage uploads.
 */
@RestController
@RequestMapping("/api/v1/storage")
@Validated
@RequiredArgsConstructor
public class StorageController {

    private final JwtService jwtService;
    private final StorageUploadApplicationService uploadApplicationService;

    /**
     * Creates a presigned upload contract for an authorized post draft.
     *
     * @param request upload scene and content metadata
     * @param jwt authenticated user JWT
     * @return storage upload contract
     */
    @RateLimit(key = "storage:presign", maxRequests = 20, windowSeconds = 60, dimension = RateLimitDimension.USER)
    @PostMapping("/presign")
    public StoragePresignResponse presign(@Valid @RequestBody StoragePresignRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        StorageUploadApplicationService.PresignResult presigned = uploadApplicationService.presign(
                userId,
                request.postId(),
                request.scene(),
                request.contentType(),
                request.ext());
        return new StoragePresignResponse(
                presigned.objectKey(),
                presigned.putUrl(),
                presigned.headers(),
                presigned.expiresIn(),
                presigned.method(),
                presigned.publicUrl());
    }

    /**
     * Accepts a validated multipart object in local-storage mode.
     *
     * @param jwt authenticated user JWT
     * @param objectKey authorized post-scoped storage key
     * @param file multipart content
     * @return entity tag for the stored bytes
     */
    @RateLimit(key = "storage:upload", maxRequests = 30, windowSeconds = 60, dimension = RateLimitDimension.USER)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StorageUploadResponse upload(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam("objectKey") String objectKey,
                                        @RequestParam("file") MultipartFile file) {
        long userId = jwtService.extractUserId(jwt);
        return new StorageUploadResponse(uploadApplicationService.upload(
                userId,
                objectKey,
                new MultipartUploadContent(file)));
    }
}
