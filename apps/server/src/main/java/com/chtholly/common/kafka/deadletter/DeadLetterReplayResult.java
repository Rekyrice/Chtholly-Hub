package com.chtholly.common.kafka.deadletter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Immutable application result returned after dead-letter queries and replay
 * lifecycle transitions.
 */
public record DeadLetterReplayResult(
        long id,
        String sourceTopic,
        String messageKey,
        String exceptionClass,
        String exceptionMessage,
        int retryCount,
        String status,
        String replayAttemptToken,
        LocalDateTime createdAt,
        LocalDateTime replayStartedAt,
        LocalDateTime replayDeadlineAt) {

    static DeadLetterReplayResult from(DeadLetterMessageRow row) {
        Objects.requireNonNull(row, "row");
        return new DeadLetterReplayResult(
                row.getId(),
                row.getSourceTopic(),
                row.getMessageKey(),
                row.getExceptionClass(),
                row.getExceptionMessage(),
                row.getRetryCount() != null ? row.getRetryCount() : 0,
                row.getStatus(),
                row.getReplayAttemptToken(),
                row.getCreatedAt(),
                row.getReplayStartedAt(),
                row.getReplayDeadlineAt());
    }
}
