package com.chtholly.counter.event;

import com.chtholly.common.kafka.AbstractKafkaConsumer;
import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * Kafka 模式下的计数事件聚合消费者。
 */
@Service
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")
public class CounterAggregationKafkaConsumer extends AbstractKafkaConsumer {

    private static final String CONSUMER_GROUP = "counter-agg";

    private final CounterAggregationProcessor processor;

    public CounterAggregationKafkaConsumer(ObjectMapper objectMapper,
                                           KafkaTemplate<String, String> kafka,
                                           DeadLetterMessageService deadLetterMessageService,
                                           CounterAggregationProcessor processor) {
        super(kafka, objectMapper, deadLetterMessageService);
        this.processor = processor;
    }

    @KafkaListener(topics = CounterTopics.EVENTS, groupId = CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consumeRecord(record, ack);
    }

    @KafkaListener(topics = CounterTopics.EVENTS + "-retry", groupId = CONSUMER_GROUP + "-retry")
    public void onRetryMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consumeRetryRecord(record, ack);
    }

    @Override
    protected void process(String sourceTopic, String messageKey, String payload, int retryCount) throws Exception {
        CounterEvent evt = objectMapper.readValue(payload, CounterEvent.class);
        processor.applyEvent(evt);
    }

    @Override
    protected String consumerName() {
        return CONSUMER_GROUP;
    }
}
