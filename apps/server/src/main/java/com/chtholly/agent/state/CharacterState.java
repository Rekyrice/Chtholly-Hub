package com.chtholly.agent.state;

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
}
