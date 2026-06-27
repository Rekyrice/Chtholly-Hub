package com.chtholly.post.model;

import lombok.Data;

import java.time.Instant;

/** 帖子详情 ETag 指纹查询行。 */
@Data
public class PostDetailEtagRow {
    private String status;
    private Instant updateTime;
}
