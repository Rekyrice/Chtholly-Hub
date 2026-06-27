package com.chtholly.common.kafka;

/**
 * 重试 topic 消息信封。
 *
 * @param sourceTopic 原始 topic
 * @param messageKey 原始 message key，可为 null
 * @param messageValue 原始消息体
 * @param retryCount 当前重试次数（首次失败后为 1）
 * @param deliverAfterEpochMs 允许再次消费的时间戳（毫秒）
 */
public record KafkaRetryEnvelope(
        String sourceTopic,
        String messageKey,
        String messageValue,
        int retryCount,
        long deliverAfterEpochMs
) {}
