package com.chtholly.counter.event;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** 非 Kafka 模式：通过 Spring ApplicationEvent 在进程内传播计数事件。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "false", matchIfMissing = true)
@ConditionalOnMissingBean(CounterEventPublisher.class)
public class SpringEventCounterPublisher implements CounterEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(CounterEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}
