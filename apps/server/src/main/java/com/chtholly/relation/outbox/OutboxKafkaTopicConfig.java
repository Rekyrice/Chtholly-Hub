package com.chtholly.relation.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Outbox 相关 Kafka 主题：应用启动时由 Spring KafkaAdmin 自动创建（若不存在）。
 */
@Configuration
public class OutboxKafkaTopicConfig {

    @Bean
    public NewTopic canalOutboxTopic() {
        return TopicBuilder.name(OutboxTopics.CANAL_OUTBOX)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic canalOutboxDlqTopic() {
        return TopicBuilder.name(OutboxTopics.CANAL_OUTBOX_DLQ)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
