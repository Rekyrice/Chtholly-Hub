package com.chtholly.counter.mapper;

/** Aggregated delta for one persisted counter snapshot generation. */
public record CounterSnapshotDelta(
        String entityType,
        String entityId,
        String metric,
        long delta,
        long factEpoch) {}
