package com.chtholly.seed;

import java.time.Instant;

/**
 * Chtholly's after-the-fact quality review for a seed post.
 */
public record SeedAuditResult(
        double qualityScore,
        String feedback,
        boolean needsReview,
        Instant auditedAt
) {
}
