package com.chtholly.tag.api.dto;

/** 标签列表项。 */
public record TagResponse(
        String id,
        String name,
        String slug,
        int usageCount
) {}
