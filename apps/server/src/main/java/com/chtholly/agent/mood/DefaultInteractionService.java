package com.chtholly.agent.mood;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Default interaction counter for mood calculation.
 *
 * <p>The project does not yet keep a consolidated interaction index, so this
 * implementation is intentionally neutral and can be replaced by a Redis/MySQL
 * backed implementation later.
 */
@Service
@ConditionalOnProperty(prefix = "agent.extensions.mood", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DefaultInteractionService implements InteractionService {

    /**
     * Counts interactions since the given lookback window.
     *
     * @param window lookback window.
     * @return zero until a real interaction index is introduced.
     */
    @Override
    public long countSince(Duration window) {
        return 0L;
    }
}
