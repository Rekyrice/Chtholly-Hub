package com.chtholly.post.model;

import lombok.Data;

import java.time.Instant;

/**
 * 帖子详情查询的行映射（含作者信息）。
 */
@Data
public class PostDetailRow {
    private Long id;
    private Long creatorId;
    private String title;
    private String slug;
    private String description;
    private String tags;        // JSON 字符串
    private String imgUrls;     // JSON 字符串
    private String contentUrl;
    private String contentEtag;
    private String contentSha256;
    private String authorHandle;
    private String authorAvatar;
    private String authorNickname;
    private String authorBio;
    private String authorTagJson;
    private Instant publishTime;
    private Boolean isTop;
    private String visible;
    private String type;
    private String status;
}
