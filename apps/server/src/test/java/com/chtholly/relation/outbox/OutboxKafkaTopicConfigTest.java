package com.chtholly.relation.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxKafkaTopicConfigTest {

    private final OutboxKafkaTopicConfig config = new OutboxKafkaTopicConfig();

    @Test
    void declaresRetryTopicWithMainTopicPartitioning() {
        NewTopic topic = config.canalOutboxRetryTopic();

        assertThat(topic.name()).isEqualTo("canal-outbox-retry");
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }
}
