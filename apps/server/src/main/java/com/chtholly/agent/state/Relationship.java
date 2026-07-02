package com.chtholly.agent.state;

import java.time.Instant;

/** Per-user relationship state. */
public record Relationship(double intimacy, long interactionCount, Instant lastSeen) {
}
