package com.chtholly.counter.event;

import com.chtholly.common.kafka.idempotency.OutboxIdempotencyGuard;
import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.mapper.CounterReactionMapper;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.service.impl.CounterReactionProjectionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Applies reaction Outbox events to Redis, durable aggregation, and existing local listeners. */
@Service
public class CounterReactionEventProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(CounterReactionEventProcessor.class);
    private static final int MYSQL_KEY_BATCH_SIZE = 500;
    private static final String SIDE_EFFECT_SCOPE = "counter-reaction-side-effects";

    private final CounterReactionMapper reactionMapper;
    private final CounterReactionProjectionStore projectionStore;
    private final CounterAggregationProcessor aggregationProcessor;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxIdempotencyGuard idempotencyGuard;
    private final CounterReactionSideEffectReceiptService sideEffectReceiptService;

    public CounterReactionEventProcessor(
            CounterReactionMapper reactionMapper,
            CounterReactionProjectionStore projectionStore,
            CounterAggregationProcessor aggregationProcessor,
            ApplicationEventPublisher eventPublisher,
            OutboxIdempotencyGuard idempotencyGuard,
            CounterReactionSideEffectReceiptService sideEffectReceiptService) {
        this.reactionMapper = Objects.requireNonNull(reactionMapper, "reactionMapper");
        this.projectionStore = Objects.requireNonNull(projectionStore, "projectionStore");
        this.aggregationProcessor =
                Objects.requireNonNull(aggregationProcessor, "aggregationProcessor");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.idempotencyGuard = Objects.requireNonNull(idempotencyGuard, "idempotencyGuard");
        this.sideEffectReceiptService =
                Objects.requireNonNull(sideEffectReceiptService, "sideEffectReceiptService");
    }

    /**
     * Applies a bounded event batch while holding the existing MySQL snapshot row locks.
     *
     * <p>The durable inbox and snapshot update acquires the entity locks first. The current
     * relation facts are then read and projected before this transaction commits, so concurrent
     * commands cannot make an older terminal-state read overwrite a newer projection.</p>
     *
     * @param events durable reaction Outbox payloads
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(List<CounterEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        List<CounterEvent> copy = List.copyOf(events);
        LinkedHashMap<CounterReactionKey, Boolean> targets = new LinkedHashMap<>();
        for (CounterEvent event : copy) {
            CounterReactionKey key = validateAndMap(event);
            targets.putIfAbsent(key, false);
        }

        CounterAggregationProcessor.ApplyBatchResult result =
                aggregationProcessor.applyBatchWithResult(copy);
        Set<CounterReactionKey> existing = loadCurrentFacts(List.copyOf(targets.keySet()));
        targets.replaceAll((key, ignored) -> existing.contains(key));
        projectionStore.project(targets);
        publishSideEffectsAfterCommit(result.sideEffectEvents());
    }

    private Set<CounterReactionKey> loadCurrentFacts(List<CounterReactionKey> keys) {
        LinkedHashSet<CounterReactionKey> existing = new LinkedHashSet<>();
        for (int from = 0; from < keys.size(); from += MYSQL_KEY_BATCH_SIZE) {
            int to = Math.min(keys.size(), from + MYSQL_KEY_BATCH_SIZE);
            List<CounterReactionKey> chunk = keys.subList(from, to);
            List<CounterReactionKey> rows = reactionMapper.findExisting(chunk);
            if (rows == null) {
                throw new IllegalStateException(
                        "Counter reaction current-state query returned null");
            }
            for (CounterReactionKey row : rows) {
                if (row == null || !chunk.contains(row)) {
                    throw new IllegalStateException(
                            "Counter reaction current-state query returned an invalid key");
                }
                existing.add(row);
            }
        }
        return Set.copyOf(existing);
    }

    private void publishSideEffectsAfterCommit(List<CounterEvent> events) {
        List<CounterEvent> copy = List.copyOf(events);
        if (copy.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishCurrentSideEffects(copy);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publishCurrentSideEffects(copy);
                    }
                });
    }

    private void publishCurrentSideEffects(List<CounterEvent> events) {
        Map<Long, CounterEvent> uniqueEvents = new LinkedHashMap<>();
        for (CounterEvent event : events) {
            validateAndMap(event);
            uniqueEvents.putIfAbsent(parseOutboxId(event.getEventId()), event);
        }
        List<CounterEvent> ordered = uniqueEvents.values().stream()
                .sorted(Comparator.comparingLong(
                        event -> parseOutboxId(event.getEventId())))
                .toList();
        RuntimeException firstFailure = null;
        for (CounterEvent event : ordered) {
            long eventId = parseOutboxId(event.getEventId());
            try {
                boolean published = sideEffectReceiptService.publishIfPending(
                        event.getEventId(),
                        () -> eventPublisher.publishEvent(event));
                if (published) {
                    markBestEffortGuard(eventId);
                }
            } catch (RuntimeException exception) {
                log.error(
                        "Counter reaction side-effect publication failed for event {}: {}",
                        eventId,
                        exception.getMessage(),
                        exception);
                if (firstFailure == null) {
                    firstFailure = exception;
                } else if (firstFailure != exception) {
                    firstFailure.addSuppressed(exception);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void markBestEffortGuard(long eventId) {
        try {
            idempotencyGuard.markConsumed(SIDE_EFFECT_SCOPE, eventId);
        } catch (RuntimeException exception) {
            log.warn(
                    "Counter reaction side-effect guard write failed for event {}: {}",
                    eventId,
                    exception.getMessage());
        }
    }

    static CounterReactionKey validateAndMap(CounterEvent event) {
        Objects.requireNonNull(event, "event");
        CounterSchema.requirePersistableIdentity(event.getEntityType(), event.getEntityId());
        int expectedIndex;
        if ("like".equals(event.getMetric())) {
            expectedIndex = CounterSchema.IDX_LIKE;
        } else if ("fav".equals(event.getMetric())) {
            expectedIndex = CounterSchema.IDX_FAV;
        } else {
            throw new IllegalArgumentException("Counter reaction metric must be like or fav");
        }
        if (event.getIdx() != expectedIndex) {
            throw new IllegalArgumentException(
                    "Counter reaction metric and index do not match");
        }
        if (event.getDelta() != 1 && event.getDelta() != -1) {
            throw new IllegalArgumentException("Counter reaction delta must be +1 or -1");
        }
        if (event.getFactEpoch() < 0L) {
            throw new IllegalArgumentException(
                    "Counter reaction fact epoch must not be negative");
        }
        parseOutboxId(event.getEventId());
        return new CounterReactionKey(
                event.getEntityType(),
                event.getEntityId(),
                event.getMetric(),
                event.getUserId());
    }

    private static long parseOutboxId(String eventId) {
        if (eventId == null || eventId.isBlank()
                || !eventId.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "Counter reaction event ID must be a positive Outbox ID");
        }
        try {
            long value = Long.parseLong(eventId);
            if (value <= 0L) {
                throw new IllegalArgumentException(
                        "Counter reaction event ID must be a positive Outbox ID");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Counter reaction event ID must be a positive Outbox ID", exception);
        }
    }
}
