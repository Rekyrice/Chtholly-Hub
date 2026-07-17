package com.chtholly.agent.content;

/**
 * Describes the public lifecycle state of a topic-cluster snapshot.
 */
public enum TopicClusterState {
    READY,
    SPARSE,
    PENDING,
    FAILED
}
