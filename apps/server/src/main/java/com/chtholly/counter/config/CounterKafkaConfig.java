package com.chtholly.counter.config;

import com.chtholly.counter.event.CounterTopics;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

/**
 * Kafka 启用时的计数模块配置：启用 Kafka 并提供字符串模板。
 */
@Configuration
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")
@EnableKafka
public class CounterKafkaConfig {

    @Bean
    public ProducerFactory<String, String> stringProducerFactory(KafkaProperties properties) {
        var props = properties.buildProducerProperties();
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer());
    }

    @Bean
    public KafkaTemplate<String, String> stringKafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean
    public ConsumerFactory<String, String> counterConsumerFactory(KafkaProperties properties) {
        return new DefaultKafkaConsumerFactory<>(
                properties.buildConsumerProperties(), new StringDeserializer(), new StringDeserializer());
    }

    private static DefaultErrorHandler counterKafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            int maxAttempts,
            Duration retryBackoff,
            Duration dltAcknowledgmentTimeout) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("counter Kafka consumer maxAttempts must be positive");
        }
        requirePositive(retryBackoff, "counter Kafka consumer retryBackoff");
        requirePositive(dltAcknowledgmentTimeout, "counter Kafka consumer dltAcknowledgmentTimeout");
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(CounterTopics.EVENTS + "-dlq", -1));
        recoverer.setVerifyPartition(false);
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(dltAcknowledgmentTimeout);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryBackoff.toMillis(), maxAttempts - 1L));
        errorHandler.setCommitRecovered(true);
        errorHandler.setAckAfterHandle(true);
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> counterBatchKafkaListenerContainerFactory(
            @Qualifier("counterConsumerFactory") ConsumerFactory<String, String> counterConsumerFactory,
            @Qualifier("stringKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            @Value("${counter.kafka.consumer.max-attempts:3}") int maxAttempts,
            @Value("${counter.kafka.consumer.retry-backoff:PT1S}") Duration retryBackoff,
            @Value("${counter.kafka.consumer.dlt-ack-timeout:PT3S}") Duration dltAcknowledgmentTimeout) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(counterConsumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(counterKafkaErrorHandler(
                kafkaTemplate, maxAttempts, retryBackoff, dltAcknowledgmentTimeout));
        return factory;
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
