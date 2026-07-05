package com.chtholly.agent.mood;

import java.time.Duration;

/**
 * Counts recent community interactions for mood calculation.
 */
public interface InteractionService {

    /**
     * Counts interactions since the given lookback window.
     *
     * @param window lookback window.
     * @return interaction count.
     */
    long countSince(Duration window);
}
