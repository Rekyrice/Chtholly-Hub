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

    /** Returns reaction event IDs whose existing local side effects still need delivery. */
    List<String> listPendingReactionSideEffectEventIds(
            @Param("eventIds") List<String> eventIds);

    /**
     * Locks one reaction Inbox row and returns whether its side effects were published.
     *
     * @return {@code 0} when pending, {@code 1} when published, or {@code null} when missing
     */
    Integer lockReactionSideEffectPublication(@Param("eventId") String eventId);

    /** Marks one reaction event after all local side-effect listeners return successfully. */
    int markReactionSideEffectsPublished(@Param("eventId") String eventId);

    /** Applies grouped deltas to durable counter snapshots. */
    void incrementSnapshots(@Param("deltas") List<CounterSnapshotDelta> deltas);

    /** Replaces like and favorite snapshots with MySQL-derived absolute values. */
    void replaceReactionSnapshots(
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("likeCount") long likeCount,
            @Param("favCount") long favCount,
            @Param("factEpoch") long factEpoch);

    /** Returns the fixed upper bound for one periodic reaction-entity sweep. */
    CounterEntityIdentity findReactionSnapshotIdentityHighWatermark();

    /** Returns one stable bounded keyset page for the periodic reaction-entity sweep. */
    List<CounterEntityIdentity> listReactionSnapshotIdentitiesPage(
            @Param("afterEntityType") String afterEntityType,
            @Param("afterEntityId") String afterEntityId,
            @Param("throughEntityType") String throughEntityType,
            @Param("throughEntityId") String throughEntityId,
            @Param("limit") int limit);
}
