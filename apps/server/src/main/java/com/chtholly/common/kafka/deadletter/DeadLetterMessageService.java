package com.chtholly.common.kafka.deadletter;

import com.chtholly.common.kafka.DeadLetterStatus;
import com.chtholly.post.id.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 死信消息持久化与查询服务。
 */
@Service
@RequiredArgsConstructor
public class DeadLetterMessageService {

    private final DeadLetterMessageMapper mapper;
    private final SnowflakeIdGenerator idGen;

    /**
     * 记录一次消费失败。
     */
    public long recordFailure(String sourceTopic,
                              String messageKey,
                              String messageValue,
                              Exception exception,
                              int retryCount,
                              DeadLetterStatus status) {
        long id = idGen.nextId();
        mapper.insert(
                id,
                sourceTopic,
                messageKey,
                messageValue,
                exception.getClass().getName(),
                exception.getMessage(),
                retryCount,
                status.name());
        return id;
    }

    public DeadLetterMessageRow findById(long id) {
        return mapper.findById(id);
    }

    public List<DeadLetterMessageRow> list(String topic, String status, int page, int size) {
        int limit = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, (Math.max(page, 1) - 1) * limit);
        return mapper.list(topic, status, limit, offset);
    }

    public long count(String topic, String status) {
        return mapper.count(topic, status);
    }

    /** Claims one dead row with a unique replay token and a database-clock deadline. */
    public boolean claimReplay(
            long id,
            String attemptToken,
            long recoveryHorizonMillis) {
        if (attemptToken == null || attemptToken.isBlank()
                || attemptToken.length() > 64) {
            throw new IllegalArgumentException(
                    "Dead-letter replay attempt token is required");
        }
        if (recoveryHorizonMillis <= 0L) {
            throw new IllegalArgumentException(
                    "Dead-letter replay recovery horizon must be positive");
        }
        return exactlyOneOrZero(mapper.claimReplay(
                id, attemptToken, recoveryHorizonMillis));
    }

    /** Completes only the replay attempt that still owns the row. */
    public boolean finishReplay(
            long id,
            String attemptToken,
            DeadLetterStatus targetStatus) {
        requireAttemptToken(attemptToken);
        Objects.requireNonNull(targetStatus, "targetStatus");
        if (targetStatus != DeadLetterStatus.PENDING
                && targetStatus != DeadLetterStatus.DEAD
                && targetStatus != DeadLetterStatus.UNCERTAIN) {
            throw new IllegalArgumentException(
                    "Dead-letter replay target status is invalid");
        }
        return exactlyOneOrZero(mapper.finishReplay(
                id, attemptToken, targetStatus.name()));
    }

    /**
     * Moves the matching expired attempt to manual uncertainty without making it replayable.
     */
    public boolean recoverExpiredReplay(
            long id,
            String attemptToken) {
        requireAttemptToken(attemptToken);
        return exactlyOneOrZero(mapper.recoverExpiredReplay(
                id, attemptToken));
    }

    /** Resolves only the matching attempt that has reached manual uncertainty. */
    public boolean resolveUncertain(
            long id,
            String attemptToken,
            DeadLetterStatus targetStatus) {
        requireAttemptToken(attemptToken);
        Objects.requireNonNull(targetStatus, "targetStatus");
        if (targetStatus != DeadLetterStatus.PENDING
                && targetStatus != DeadLetterStatus.DEAD) {
            throw new IllegalArgumentException(
                    "Dead-letter uncertainty target status is invalid");
        }
        return exactlyOneOrZero(mapper.resolveUncertain(
                id, attemptToken, targetStatus.name()));
    }

    private static void requireAttemptToken(String attemptToken) {
        if (attemptToken == null || attemptToken.isBlank()
                || attemptToken.length() > 64) {
            throw new IllegalArgumentException(
                    "Dead-letter replay attempt token is required");
        }
    }

    private static boolean exactlyOneOrZero(int updated) {
        if (updated < 0 || updated > 1) {
            throw new IllegalStateException(
                    "Dead-letter status transition returned an invalid row count");
        }
        return updated == 1;
    }
}
