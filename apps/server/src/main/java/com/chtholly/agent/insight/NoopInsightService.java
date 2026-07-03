package com.chtholly.agent.insight;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default insight provider used before the learning loop is available.
 */
@Service
public class NoopInsightService implements InsightService {

    @Override
    public List<String> getActiveInsights(long userId, int limit) {
        return List.of();
    }
}
