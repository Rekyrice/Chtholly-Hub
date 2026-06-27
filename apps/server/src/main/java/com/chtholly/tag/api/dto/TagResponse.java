package com.chtholly.tag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 标签列表项。 */
@Schema(description = "标签")
public record TagResponse(
        @Schema(description = "标签 ID") String id,
        @Schema(description = "标签名") String name,
        @Schema(description = "URL slug") String slug,
        @Schema(description = "引用次数") int usageCount
) {}
