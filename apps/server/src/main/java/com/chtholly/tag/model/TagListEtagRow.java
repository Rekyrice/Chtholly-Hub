package com.chtholly.tag.model;

import lombok.Data;

import java.time.Instant;

/** 标签列表 ETag 指纹。 */
@Data
public class TagListEtagRow {
    private Long tagCount;
    private Instant maxUpdatedAt;
}
