package com.chtholly.common.kafka;

import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Kafka 消费者通用基类：统一 try/catch、死信落库、重试 topic 与 DLQ 转发，并始终 ack 当前消息。
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractKafkaConsumer {

    protected static final int MAX_RETRY_COUNT = 3;

    protected final KafkaTemplate<String, String> kafkaTemplate;
    protected final ObjectMapper objectMapper;
    protected final DeadLetterMessageService deadLetterMessageService;

    /**
     * 消费原始 topic 消息。
     */
    protected void consumeMessage(String sourceTopic, String messageKey, String payload, Acknowledgment ack) {
        consumeMessage(sourceTopic, messageKey, payload, 0, ack);
    }

    /**
     * 消费消息（含重试次数）。
     */
    protected void consumeMessage(String sourceTopic,
                                  String messageKey,
                                  String payload,
                                  int retryCount,
                                  Acknowledgment ack) {
        try {
            process(sourceTopic, messageKey, payload, retryCount);
        } catch (Exception e) {
            handleError(sourceTopic, messageKey, payload, retryCount, e);
        } finally {
            ack.acknowledge();
        }
    }

    /**
     * 消费重试 topic 上的信封消息。
     */
    protected void consumeRetryEnvelope(String envelopeJson, Acknowledgment ack) {
        try {
            KafkaRetryEnvelope envelope = objectMapper.readValue(envelopeJson, KafkaRetryEnvelope.class);
            if (envelope.deliverAfterEpochMs() > System.currentTimeMillis()) {
                kafkaTemplate.send(KafkaTopicNames.retryTopic(envelope.sourceTopic()), envelopeJson);
                return;
            }
            process(envelope.sourceTopic(), envelope.messageKey(), envelope.messageValue(), envelope.retryCount());
        } catch (Exception e) {
            log.error("[{}] Failed to consume retry envelope: {}", consumerName(), e.getMessage(), e);
            handleError("unknown", null, envelopeJson, MAX_RETRY_COUNT, e);
        } finally {
            ack.acknowledge();
        }
    }

    /**
     * 子类实现具体业务处理逻辑。
     */
    protected abstract void process(String sourceTopic, String messageKey, String payload, int retryCount) throws Exception;

    /**
     * 子类名称，用于日志标识。
     */
    protected abstract String consumerName();

    private void handleError(String sourceTopic,
                             String messageKey,
                             String payload,
                             int retryCount,
                             Exception exception) {
        log.error("[{}] Kafka consume failed on topic={}, retryCount={}: {}",
                consumerName(), sourceTopic, retryCount, exception.getMessage(), exception);

        DeadLetterStatus status = retryCount >= MAX_RETRY_COUNT ? DeadLetterStatus.DEAD : DeadLetterStatus.RETRYING;
        deadLetterMessageService.recordFailure(sourceTopic, messageKey, payload, exception, retryCount, status);

        if (retryCount < MAX_RETRY_COUNT) {
            sendToRetryTopic(sourceTopic, messageKey, payload, retryCount + 1);
        } else {
            sendToDlqTopic(sourceTopic, messageKey, payload, retryCount, exception);
        }
    }

    private void sendToRetryTopic(String sourceTopic, String messageKey, String payload, int nextRetryCount) {
        try {
            KafkaRetryEnvelope envelope = new KafkaRetryEnvelope(
                    sourceTopic,
                    messageKey,
                    payload,
                    nextRetryCount,
                    KafkaRetryDelays.deliverAfterEpochMs(nextRetryCount));
            String json = objectMapper.writeValueAsString(envelope);
            kafkaTemplate.send(KafkaTopicNames.retryTopic(sourceTopic), messageKey, json);
        } catch (Exception ex) {
            log.error("[{}] Failed to publish retry message for topic={}: {}",
                    consumerName(), sourceTopic, ex.getMessage(), ex);
        }
    }

    private void sendToDlqTopic(String sourceTopic,
                                String messageKey,
                                String payload,
                                int retryCount,
                                Exception exception) {
        try {
            KafkaRetryEnvelope dlqEnvelope = new KafkaRetryEnvelope(
                    sourceTopic,
                    messageKey,
                    payload,
                    retryCount,
                    System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(dlqEnvelope);
            kafkaTemplate.send(KafkaTopicNames.dlqTopic(sourceTopic), messageKey, json);
        } catch (Exception ex) {
            log.error("[{}] Failed to publish DLQ message for topic={}: {}",
                    consumerName(), sourceTopic, ex.getMessage(), ex);
        }
    }
}
