package com.chtholly.agent.eval;

import java.util.List;

/**
 * 基于 expectedKeywords 的知识准确性补充验证。
 */
final class KeywordGroundingScorer {

    private KeywordGroundingScorer() {
    }

    static EvaluationScore score(String response, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        long hits = keywords.stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .filter(keyword -> response != null && response.contains(keyword))
                .count();
        long total = keywords.stream().filter(keyword -> keyword != null && !keyword.isBlank()).count();
        if (total == 0) {
            return null;
        }
        int score = (int) Math.round(hits * 5.0 / total);
        if (hits == 0) {
            score = 1;
        } else {
            score = Math.max(1, Math.min(5, score));
        }
        return new EvaluationScore(score, "关键词命中 " + hits + "/" + total);
    }
}
