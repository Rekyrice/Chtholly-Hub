package com.chtholly.counter.event;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 非 Kafka 模式：监听 Spring ApplicationEvent 做计数聚合。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "false", matchIfMissing = true)
public class CounterAggregationSpringConsumer {

    private final CounterAggregationProcessor processor;

    @EventListener
    public void onCounterEvent(CounterEvent event) {
        processor.applyBatch(List.of(event));
    }
}
