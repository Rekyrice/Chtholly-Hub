package com.chtholly.seed;

import java.time.Instant;

/**
 * Admin-facing seed audit result.
 */
public record SeedAuditResultResponse(
        long postId,
        double qualityScore,
        String feedback,
        boolean needsReview,
        Instant auditedAt
) {
}
