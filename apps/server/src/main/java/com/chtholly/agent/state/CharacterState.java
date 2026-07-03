package com.chtholly.agent.state;

import java.time.Instant;

/**
 * The living character state that evolves through user interactions.
 *
 * @param personality  Stable personality weights.
 * @param mood         Current emotional state.
 * @param relationship Per-user relationship data.
 * @param needs        Internal need satisfaction levels.
 * @param behaviorProb Probabilities for proactive behaviors.
 */
public record CharacterState(
        Personality personality,
        Mood mood,
        Relationship relationship,
        Needs needs,
        BehaviorProb behaviorProb
) {
    /**
     * Creates a stable default state for fallback paths.
     *
     * @return Default character state.
     */
    public static CharacterState defaultState() {
        return defaultState(Instant.EPOCH);
    }

    /**
     * Creates a default state with a caller-provided last-seen timestamp.
     *
     * @param lastSeen Last seen timestamp.
     * @return Default character state.
     */
    public static CharacterState defaultState(Instant lastSeen) {
        return new CharacterState(
                new Personality(0.7, 0.8, 0.5),
                new Mood(0.0, 0.5, 0.0),
                new Relationship(0.0, 0, lastSeen),
                new Needs(0.0, 0.0, 0.0),
                new BehaviorProb(0.5, 0.3, 0.3));
    }
}
