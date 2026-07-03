package com.chtholly.agent.anchor;

import com.chtholly.agent.learning.InsightService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides procedural behavior rules learned from previous conversations.
 */
@Service
public class ProceduralMemoryService {

    private final InsightService insightService;

    public ProceduralMemoryService(InsightService insightService) {
        this.insightService = insightService;
    }

    /**
     * Returns top learned behavior rules within a prompt character budget.
     *
     * @param userId   Authenticated user ID.
     * @param topN     Maximum number of rules.
     * @param maxChars Maximum total characters.
     * @return Learned behavior rules.
     */
    public List<String> getTopRules(long userId, int topN, int maxChars) {
        List<String> insights = insightService.getActiveInsights(userId, topN);
        if (insights == null || insights.isEmpty()) {
            return List.of();
        }

        List<String> rules = new ArrayList<>();
        int chars = 0;
        for (String insight : insights) {
            if (insight == null || insight.isBlank()) {
                continue;
            }
            String rule = insight.trim();
            if (chars + rule.length() > maxChars) {
                break;
            }
            rules.add(rule);
            chars += rule.length();
        }
        return List.copyOf(rules);
    }
}
