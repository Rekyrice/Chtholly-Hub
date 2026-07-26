package com.chtholly.counter.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Objects;

/** Dispatches committed reaction Outbox payloads when Kafka is disabled. */
@Component
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "false", matchIfMissing = true)
public class CounterReactionLocalAdapter {

    private static final Logger log = LoggerFactory.getLogger(CounterReactionLocalAdapter.class);

    private final CounterReactionEventProcessor processor;

    public CounterReactionLocalAdapter(CounterReactionEventProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    /**
     * Runs the shared projection only after the authoritative command transaction commits.
     *
     * @param committed exact event persisted in the Outbox
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(CounterReactionCommittedEvent committed) {
        try {
            processor.process(List.of(committed.event()));
        } catch (RuntimeException exception) {
            // MySQL 已提交；保留事实并由 Outbox 重放或周期校准恢复投影。
            log.error(
                    "Local counter reaction projection failed eventId={}: {}",
                    committed.event().getEventId(),
                    exception.getMessage(),
                    exception);
        }
    }
}
