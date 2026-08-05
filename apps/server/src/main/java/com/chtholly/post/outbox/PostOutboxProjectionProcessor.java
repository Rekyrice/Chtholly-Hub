package com.chtholly.post.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Runs one post projection and records its durable completion fact only after success. */
@Service
public class PostOutboxProjectionProcessor {

    private final PostProjectionReceiptMapper receiptMapper;
    private final PostOutboxProjectionService projectionService;

    public PostOutboxProjectionProcessor(
            PostProjectionReceiptMapper receiptMapper,
            PostOutboxProjectionService projectionService) {
        this.receiptMapper = Objects.requireNonNull(receiptMapper, "receiptMapper");
        this.projectionService = Objects.requireNonNull(projectionService, "projectionService");
    }

    /**
     * Projects one durable event while holding its post cursor lock.
     *
     * <p>A new transaction is required because the fast path is also invoked from an
     * {@code afterCommit} callback, where the just-committed transaction may still be bound to
     * the thread.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(long eventId, String eventType, long postId) {
        requireValid(eventId, eventType, postId);

        int cursorInserted = receiptMapper.insertCursorIfAbsent(postId);
        if (cursorInserted != 0 && cursorInserted != 1) {
            throw new IllegalStateException(
                    "Post projection cursor insert affected " + cursorInserted + " rows for post " + postId);
        }
        Long previousEventId = receiptMapper.lockCursor(postId);
        if (previousEventId == null) {
            throw new IllegalStateException("Post projection cursor is missing for post " + postId);
        }

        int receiptCount = receiptMapper.countReceipt(eventId);
        if (receiptCount == 1) {
            return;
        }
        if (receiptCount != 0) {
            throw new IllegalStateException(
                    "Post projection receipt count is invalid for event " + eventId);
        }

        if (eventId <= previousEventId) {
            int parentCount = receiptMapper.countOutboxEvent(eventId);
            if (parentCount == 0) {
                return;
            }
            if (parentCount != 1) {
                throw new IllegalStateException(
                        "Post Outbox parent count is invalid for event " + eventId);
            }
        }

        projectionService.project(eventType, postId);
        int receiptInserted = receiptMapper.insertReceipt(eventId, postId);
        if (receiptInserted != 1) {
            throw new IllegalStateException(
                    "Post projection receipt insert affected " + receiptInserted + " rows for event " + eventId);
        }
        if (eventId <= previousEventId) {
            return;
        }
        int advanced = receiptMapper.advanceCursor(postId, previousEventId, eventId);
        if (advanced != 1) {
            throw new IllegalStateException(
                    "Post projection cursor advance affected " + advanced + " rows for event " + eventId);
        }
    }

    private static void requireValid(long eventId, String eventType, long postId) {
        if (eventId <= 0 || postId <= 0 || !PostOutboxProjectionService.supports(eventType)) {
            throw new IllegalArgumentException("Post projection event is invalid");
        }
    }
}
