package com.chtholly.post.outbox;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Persists durable completion receipts and reads pending post Outbox rows. */
@Mapper
public interface PostProjectionReceiptMapper {

    /** Minimal immutable row required by the local projection replay. */
    record ReplayRow(long id, String type, long postId) {}

    int countReceipt(@Param("eventId") long eventId);

    /** Checks whether the durable parent row still exists after receipt cleanup cascades. */
    int countOutboxEvent(@Param("eventId") long eventId);

    /** Creates the row that acts as the cross-JVM mutex for one post. */
    int insertCursorIfAbsent(@Param("postId") long postId);

    /** Locks one post's projection cursor until the surrounding transaction completes. */
    Long lockCursor(@Param("postId") long postId);

    int insertReceipt(
            @Param("eventId") long eventId,
            @Param("postId") long postId);

    /** Advances only from the value observed while holding the cursor lock. */
    int advanceCursor(
            @Param("postId") long postId,
            @Param("previousEventId") long previousEventId,
            @Param("eventId") long eventId);

    /** Captures the upper bound for one finite scan. */
    Long findReplayHighWatermark();

    /** Returns one ordered page of rows that have no durable completion receipt. */
    List<ReplayRow> listReplayPage(
            @Param("afterId") long afterId,
            @Param("throughId") long throughId,
            @Param("limit") int limit);
}
