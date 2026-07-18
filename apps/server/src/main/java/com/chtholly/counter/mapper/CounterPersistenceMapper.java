package com.chtholly.counter.mapper;

import com.chtholly.counter.event.CounterEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Persists durable counter-event idempotency and convergent snapshots. */
@Mapper
public interface CounterPersistenceMapper {

    /** Inserts an event ID if it has not been applied before. */
    int insertInbox(CounterEvent event);

    /** Confirms that an existing event ID represents the exact same counter mutation. */
    int countMatchingInbox(CounterEvent event);

    /** Applies grouped deltas to durable counter snapshots. */
    void incrementSnapshots(@Param("deltas") List<CounterSnapshotDelta> deltas);
}
