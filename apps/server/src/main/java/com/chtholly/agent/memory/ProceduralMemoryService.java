package com.chtholly.agent.memory;

import com.chtholly.agent.learning.InsightService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Backward-compatible procedural-memory facade.
 *
 * <p>The canonical rule store is owned by {@link InsightService}; this facade keeps the
 * original API available without maintaining a second Redis namespace.
 */
@Service
public class ProceduralMemoryService {

    private final InsightService insightService;

    public ProceduralMemoryService(InsightService insightService) {
        this.insightService = insightService;
    }

    public void storeRule(long userId, String ruleText) {
        insightService.storeRule(userId, ruleText);
    }

    public List<String> getTopRules(long userId, int topN, int maxChars) {
        return insightService.getTopRules(userId, topN, maxChars);
    }

    public void recordRuleUsage(long userId, String ruleId) {
        insightService.recordRuleUsage(userId, ruleId);
    }

    public void recordNegativeFeedback(long userId, String ruleId) {
        insightService.recordNegativeFeedback(userId, ruleId);
    }
}
