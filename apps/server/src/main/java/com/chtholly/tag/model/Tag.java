package com.chtholly.tag.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** 标签实体，对应表 {@code tags}。 */
@Data
@Builder
public class Tag {
    private Long id;
    private String name;
    private String slug;
    private Long creatorId;
    private int usageCount;
    private Instant createdAt;
    private Instant updatedAt;
}
