package com.chtholly.counter.mapper;

/** One locked reaction snapshot epoch row. */
public record CounterSnapshotEpoch(
        String entityType,
        String entityId,
        String metric,
        long factEpoch) {}
