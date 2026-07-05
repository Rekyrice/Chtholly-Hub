package com.chtholly.seed;

import java.util.List;

/**
 * Planned seed post before ID and generated body are assigned.
 */
public record SeedPostPlan(
        String title,
        String category,
        List<String> tags,
        int daysAgo,
        int slot
) {
}
