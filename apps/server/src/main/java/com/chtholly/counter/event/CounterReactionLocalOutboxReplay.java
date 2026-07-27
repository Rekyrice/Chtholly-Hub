package com.chtholly.counter.event;

import com.chtholly.counter.service.CounterReactionCommandService;
import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.service.impl.CounterReactionProjectionStore.ProjectionBatchException;
import com.chtholly.relation.outbox.OutboxMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Replays durable reaction Outbox rows that local post-commit delivery did not finish. */
@Component
@ConditionalOnExpression("!${kafka.enabled:false} || !${canal.enabled:false}")
public class CounterReactionLocalOutboxReplay {

    private static final Logger log =
            LoggerFactory.getLogger(CounterReactionLocalOutboxReplay.class);

    private final OutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final CounterReactionEventProcessor processor;
    private final int batchSize;
    private long scanCursor;
    private long scanHighWatermark;

    public CounterReactionLocalOutboxReplay(
            OutboxMapper outboxMapper,
            ObjectMapper objectMapper,
            CounterReactionEventProcessor processor,
            @Value("${counter.reaction.local-replay.batch-size:100}") int batchSize) {
        this.outboxMapper = Objects.requireNonNull(outboxMapper, "outboxMapper");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.processor = Objects.requireNonNull(processor, "processor");
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException(
                    "counter reaction local replay batch size must be between 1 and 500");
        }
        this.batchSize = batchSize;
    }

    /** Runs one bounded retry pass; unsuccessful rows remain discoverable in MySQL. */
    @Scheduled(
            fixedDelayString = "${counter.reaction.local-replay.fixed-delay:PT5S}",
            initialDelayString = "${counter.reaction.local-replay.initial-delay:PT5S}")
    public synchronized void replayPending() {
        try {
            List<OutboxMapper.ReactionReplayRow> rows = loadNextPage();
            if (rows.isEmpty()) {
                return;
            }

            List<CounterEvent> events = new ArrayList<>(rows.size());
            for (OutboxMapper.ReactionReplayRow row : rows) {
                if (!row.pending()) {
                    continue;
                }
                try {
                    events.add(map(row));
                } catch (IllegalArgumentException exception) {
                    log.error(
                            "Local counter reaction Outbox row {} is invalid: {}",
                            row.id(),
                            exception.getMessage(),
                            exception);
                }
            }
            if (events.isEmpty()) {
                return;
            }
            processIsolatingFailures(List.copyOf(events));
        } catch (RuntimeException exception) {
            log.error(
                    "Local counter reaction Outbox replay failed: {}",
                    exception.getMessage(),
                    exception);
        }
    }

    /**
     * Keeps the normal path batched. Exact relation keys rejected by a completed Redis pipeline
     * stay pending while all other events retry once as one batch; unattributed failures stop the
     * page without amplification.
     */
    private void processIsolatingFailures(List<CounterEvent> events) {
        try {
            processor.process(events);
        } catch (ProjectionBatchException exception) {
            List<CounterEvent> healthy = withoutFailedKeys(events, exception.failedKeys());
            if (healthy.size() == events.size()) {
                throw exception;
            }
            if (!healthy.isEmpty()) {
                try {
                    processor.process(healthy);
                } catch (RuntimeException healthyFailure) {
                    if (healthyFailure != exception) {
                        healthyFailure.addSuppressed(exception);
                    }
                    throw healthyFailure;
                }
            }
            List<String> failedEventIds = events.stream()
                    .filter(event -> exception.failedKeys().contains(
                            CounterReactionEventProcessor.validateAndMap(event)))
                    .map(CounterEvent::getEventId)
                    .toList();
            log.error(
                    "Local counter reaction Outbox events {} remain pending: {}",
                    failedEventIds,
                    exception.getMessage(),
                    exception);
        }
    }

    private static List<CounterEvent> withoutFailedKeys(
            List<CounterEvent> events,
            Set<CounterReactionKey> failedKeys) {
        return events.stream()
                .filter(event -> !failedKeys.contains(
                        CounterReactionEventProcessor.validateAndMap(event)))
                .toList();
    }

    private List<OutboxMapper.ReactionReplayRow> loadNextPage() {
        if (scanHighWatermark == 0L) {
            Long highWatermark =
                    outboxMapper.findCounterReactionReplayHighWatermark();
            if (highWatermark == null || highWatermark < 0L) {
                throw new IllegalStateException(
                        "Counter reaction local replay high watermark is invalid");
            }
            if (highWatermark == 0L) {
                return List.of();
            }
            scanHighWatermark = highWatermark;
        }

        long afterId = scanCursor;
        long throughId = scanHighWatermark;
        List<OutboxMapper.ReactionReplayRow> rows =
                outboxMapper.listCounterReactionReplayPage(
                        afterId, throughId, batchSize);
        if (rows == null) {
            throw new IllegalStateException(
                    "Counter reaction local replay query returned no result");
        }
        if (rows.isEmpty()) {
            resetScanWindow();
            return List.of();
        }

        long previousId = afterId;
        for (OutboxMapper.ReactionReplayRow row : rows) {
            if (row == null
                    || row.id() <= previousId
                    || row.id() > throughId) {
                throw new IllegalStateException(
                        "Counter reaction local replay page is outside its ordered scan window");
            }
            previousId = row.id();
        }
        if (previousId >= throughId) {
            resetScanWindow();
        } else {
            scanCursor = previousId;
        }
        return rows;
    }

    private void resetScanWindow() {
        scanCursor = 0L;
        scanHighWatermark = 0L;
    }

    private CounterEvent map(OutboxMapper.ReactionReplayRow row) {
        if (row == null || row.id() <= 0L
                || !CounterReactionCommandService.OUTBOX_EVENT_TYPE.equals(row.type())
                || row.payload() == null || row.payload().isBlank()) {
            throw new IllegalArgumentException(
                    "Counter reaction local replay row is invalid");
        }
        try {
            CounterEvent event = objectMapper.readValue(row.payload(), CounterEvent.class);
            if (event == null) {
                throw new IllegalArgumentException(
                        "Counter reaction local replay payload must contain an event");
            }
            if (!Long.toString(row.id()).equals(event.getEventId())) {
                throw new IllegalArgumentException(
                        "Counter reaction Outbox event ID does not match payload event ID");
            }
            CounterReactionEventProcessor.validateAndMap(event);
            return event;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Counter reaction local replay payload is invalid", exception);
        }
    }
}
