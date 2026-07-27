package com.chtholly.counter.mapper;

/** Identifies one entity whose reaction projections can be rebuilt from MySQL facts. */
public record CounterEntityIdentity(String entityType, String entityId) {}
