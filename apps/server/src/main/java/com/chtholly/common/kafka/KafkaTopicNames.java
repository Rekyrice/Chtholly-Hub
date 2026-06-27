package com.chtholly.common.kafka;

/** Kafka topic 命名辅助。 */
public final class KafkaTopicNames {

    private KafkaTopicNames() {
    }

    public static String retryTopic(String sourceTopic) {
        return sourceTopic + "-retry";
    }

    public static String dlqTopic(String sourceTopic) {
        return sourceTopic + "-dlq";
    }
}
