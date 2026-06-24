package com.chtholly.storage.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 预签名直传请求。
 */
public record StoragePresignRequest(
        @NotBlank String scene, // post_content | post_image
        @NotBlank String postId, // 字符串避免前端精度丢失
        @NotBlank String contentType,
        String ext
) {}