package com.chtholly.agent.state;

import java.time.Instant;

/**
 * Fast-changing emotion state caused by recent interaction events.
 *
 * @param label       emotion label.
 * @param intensity   current intensity from 0.0 to 1.0.
 * @param triggeredAt time when the emotion was triggered.
 * @param trigger     source of this emotion.
 */
public record EmotionState(
        String label,
        double intensity,
        Instant triggeredAt,
        String trigger
) {
}
