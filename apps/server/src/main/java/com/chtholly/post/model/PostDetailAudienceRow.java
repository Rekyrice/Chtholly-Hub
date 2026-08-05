package com.chtholly.post.model;

import lombok.Data;

/** Lightweight authoritative audience snapshot used to authorize detail-cache payloads. */
@Data
public class PostDetailAudienceRow {
    private Long creatorId;
    private String status;
    private String visible;
}
