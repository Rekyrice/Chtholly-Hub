package com.chtholly.agent.state;

/** Current emotional state for the character. */
public record Mood(double valence, double arousal, double baseline) {
}
