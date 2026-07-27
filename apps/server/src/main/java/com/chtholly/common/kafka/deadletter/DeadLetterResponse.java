package com.chtholly.common.kafka.deadletter;

import java.time.LocalDateTime;

/** 死信消息 API 响应项；replayAttemptToken 是并发代际标识，不是授权凭证。 */
public record DeadLetterResponse(
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
        LocalDateTime replayDeadlineAt
) {
    static DeadLetterResponse from(DeadLetterMessageRow row) {
        return new DeadLetterResponse(
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
