package com.chtholly.counter.mapper;

import com.chtholly.counter.event.CounterEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Persists durable counter-event idempotency and convergent snapshots. */
@Mapper
public interface CounterPersistenceMapper {

    /** Ensures both reaction snapshot rows exist before an entity-level lock is acquired. */
    int ensureReactionSnapshots(
            @Param("entityType") String entityType,
            @Param("entityId") String entityId);

    /** Locks both reaction snapshot rows in metric order and returns their epochs. */
    List<Long> lockReactionEpochs(
            @Param("entityType") String entityType,
            @Param("entityId") String entityId);

    /** Ensures reaction snapshot rows for a bounded entity batch. */
    int ensureReactionSnapshotsBatch(
            @Param("identities") List<CounterEntityIdentity> identities);

    /** Locks a bounded entity batch in deterministic identity and metric order. */
    List<CounterSnapshotEpoch> lockReactionSnapshotEpochs(
            @Param("identities") List<CounterEntityIdentity> identities);

    /** Inserts an event ID if it has not been applied before. */
    int insertInbox(CounterEvent event);

    /** Confirms that an existing event ID represents the exact same counter mutation. */
    int countMatchingInbox(CounterEvent event);

    /** Applies grouped deltas to durable counter snapshots. */
    void incrementSnapshots(@Param("deltas") List<CounterSnapshotDelta> deltas);

    /** Replaces like and favorite snapshots with Bitmap-derived absolute values. */
    void replaceReactionSnapshots(
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("likeCount") long likeCount,
            @Param("favCount") long favCount,
            @Param("factEpoch") long factEpoch);

    /** Returns the least recently calibrated reaction entities. */
    List<CounterEntityIdentity> listOldestReactionSnapshotIdentities(@Param("limit") int limit);
}
