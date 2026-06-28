package com.chtholly.counter.event;

/** 计数事件发布接口：Kafka 或 Spring ApplicationEvent 实现。 */
public interface CounterEventPublisher {

    void publish(CounterEvent event);
}
