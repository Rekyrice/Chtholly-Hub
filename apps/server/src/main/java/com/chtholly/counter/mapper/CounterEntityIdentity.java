package com.chtholly.counter.mapper;

/** Identifies one entity whose reaction counts can be rebuilt from Bitmap facts. */
public record CounterEntityIdentity(String entityType, String entityId) {}
