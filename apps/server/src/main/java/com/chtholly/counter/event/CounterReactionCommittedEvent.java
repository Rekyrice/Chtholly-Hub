package com.chtholly.counter.event;

import java.util.Objects;

/**
 * Registers a durable reaction Outbox event for post-commit local processing.
 *
 * @param event the exact event persisted in the Outbox row
 */
public record CounterReactionCommittedEvent(CounterEvent event) {

    public CounterReactionCommittedEvent {
        Objects.requireNonNull(event, "event");
    }
}
