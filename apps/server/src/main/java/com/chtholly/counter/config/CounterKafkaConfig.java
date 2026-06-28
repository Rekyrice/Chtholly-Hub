package com.chtholly.counter.config;

import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

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
}
