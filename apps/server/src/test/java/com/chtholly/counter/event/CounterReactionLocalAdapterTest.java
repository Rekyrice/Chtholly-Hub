package com.chtholly.counter.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CounterReactionLocalAdapterTest {

    @Mock
    private CounterReactionEventProcessor processor;

    @Test
    void delegatesTheExactOutboxPayloadToTheSharedCoreAfterCommit() {
        CounterEvent event = CounterEvent.of("41", "post", "7", "like", 1, 42L, 1);
        CounterReactionLocalAdapter adapter = new CounterReactionLocalAdapter(processor);

        adapter.onCommitted(new CounterReactionCommittedEvent(event));

        verify(processor).process(List.of(event));
    }

    @Test
    void localProjectionFailureDoesNotUndoTheAlreadyCommittedMysqlFact() {
        CounterEvent event = CounterEvent.of("41", "post", "7", "like", 1, 42L, 1);
        CounterReactionLocalAdapter adapter = new CounterReactionLocalAdapter(processor);
        doThrow(new IllegalStateException("redis down")).when(processor).process(List.of(event));

        adapter.onCommitted(new CounterReactionCommittedEvent(event));

        verify(processor).process(List.of(event));
    }

    @Test
    void listenerIsExplicitlyBoundToAfterCommit() throws Exception {
        Method method = CounterReactionLocalAdapter.class.getMethod(
                "onCommitted", CounterReactionCommittedEvent.class);
        TransactionalEventListener annotation =
                method.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void registersLocalAdapterWhenKafkaIsDisabled() {
        transportContext("kafka.enabled=false", "canal.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(CounterReactionLocalAdapter.class));
    }

    @Test
    void registersLocalAdapterWhenCanalIsDisabledEvenWithKafkaEnabled() {
        transportContext("kafka.enabled=true", "canal.enabled=false")
                .run(context -> assertThat(context)
                        .hasSingleBean(CounterReactionLocalAdapter.class));
    }

    @Test
    void omitsLocalAdapterOnlyWhenTheCompleteKafkaTransportIsEnabled() {
        transportContext("kafka.enabled=true", "canal.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CounterReactionLocalAdapter.class));
    }

    private ApplicationContextRunner transportContext(String... properties) {
        return new ApplicationContextRunner()
                .withBean(CounterReactionEventProcessor.class, () -> processor)
                .withUserConfiguration(CounterReactionLocalAdapter.class)
                .withPropertyValues(properties);
    }
}
