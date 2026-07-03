package com.chtholly.agent.insight;

import java.util.List;

/**
 * Provides learned behavior rules that should influence agent responses.
 *
 * <p>The first implementation is intentionally empty; the learning pipeline will
 * populate active insights in a later iteration.
 */
public interface InsightService {

    /**
     * Returns active insights for a user, ordered by relevance.
     *
     * @param userId Authenticated user ID.
     * @param limit  Maximum number of insights to return.
     * @return Learned behavior rules that can be injected into the prompt.
     */
    List<String> getActiveInsights(long userId, int limit);
}
