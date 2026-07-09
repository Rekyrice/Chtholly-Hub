package com.chtholly.agent.quality;

import java.util.Map;

/**
 * Quality evaluation result with aggregate and per-dimension scores.
 */
public record QualityResult(
        double score,
        String feedback,
        boolean passed,
        Map<String, Double> dimensionScores
) {
}
