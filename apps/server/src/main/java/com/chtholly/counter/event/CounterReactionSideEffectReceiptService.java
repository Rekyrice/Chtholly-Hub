package com.chtholly.counter.event;

import com.chtholly.counter.mapper.CounterPersistenceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Persists the narrow receipt used to replay reaction side effects safely. */
@Service
public class CounterReactionSideEffectReceiptService {

    private final CounterPersistenceMapper persistenceMapper;

    public CounterReactionSideEffectReceiptService(
            CounterPersistenceMapper persistenceMapper) {
        this.persistenceMapper =
                Objects.requireNonNull(persistenceMapper, "persistenceMapper");
    }

    /**
     * Serializes local publication for one event and records its durable receipt.
     *
     * @param eventId stable reaction Outbox ID
     * @param publication synchronous Spring event publication
     * @return whether this caller performed the publication
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean publishIfPending(String eventId, Runnable publication) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("Counter reaction event ID is required");
        }
        Objects.requireNonNull(publication, "publication");
        Integer published =
                persistenceMapper.lockReactionSideEffectPublication(eventId);
        if (published == null) {
            throw new IllegalStateException(
                    "Counter reaction side-effect receipt row is missing");
        }
        if (published == 1) {
            return false;
        }
        if (published != 0) {
            throw new IllegalStateException(
                    "Counter reaction side-effect receipt state is invalid");
        }

        publication.run();
        int updated = persistenceMapper.markReactionSideEffectsPublished(eventId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Counter reaction side-effect receipt was not persisted");
        }
        return true;
    }
}
