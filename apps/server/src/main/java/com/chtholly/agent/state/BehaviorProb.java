package com.chtholly.agent.state;

/** Probabilities for proactive character behaviors. */
public record BehaviorProb(double proactiveGreet, double shareObservation, double recommendPost) {
}
