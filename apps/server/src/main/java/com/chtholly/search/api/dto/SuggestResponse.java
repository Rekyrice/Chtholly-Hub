package com.chtholly.search.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 搜索联想建议响应。 */
@Schema(description = "搜索联想建议")
public record SuggestResponse(
        @Schema(description = "候选标题列表") List<String> items
) {}
