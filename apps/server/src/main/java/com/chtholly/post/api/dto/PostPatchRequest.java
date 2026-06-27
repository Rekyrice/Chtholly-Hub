package com.chtholly.post.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 帖子元数据更新请求。 */
@Schema(description = "帖子元数据更新")
public record PostPatchRequest(
        @Schema(description = "标题") String title,
        @Schema(description = "主标签 ID") Long tagId,
        @Schema(description = "标签名列表") @Size(max = 20) List<String> tags,
        @Schema(description = "图片 URL 列表") @Size(max = 20) List<String> imgUrls,
        @Schema(description = "可见性：public / private") String visible,
        @Schema(description = "是否置顶") Boolean isTop,
        @Schema(description = "摘要描述") String description
) {}
