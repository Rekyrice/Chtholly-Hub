package com.chtholly.post.draftedit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Persisted, immutable-content preview for one explicitly approved draft edit. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftEditPreview {

    public static final String PENDING = "PENDING";
    public static final String APPLIED = "APPLIED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";

    private Long id;
    private Long ownerId;
    private Long draftId;
    private String skillId;
    private String skillVersion;
    private String baseContentSha256;
    private String candidateContent;
    private String candidateContentSha256;
    private String previewHash;
    private String status;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant decidedAt;
}
