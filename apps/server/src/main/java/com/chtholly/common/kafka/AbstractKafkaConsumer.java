package com.chtholly.common.kafka;

import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chtholly.common.tracing.CorrelationIdSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.concurrent.TimeUnit;

/**
 * Kafka 消费者通用基类：统一失败记录与重试/DLQ 转发，broker 确认后才 ack 当前消息。
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractKafkaConsumer {

    protected static final int MAX_RETRY_COUNT = 3;
    private static final long FORWARD_ACK_TIMEOUT_SECONDS = 10;

    protected final KafkaTemplate<String, String> kafkaTemplate;
    protected final ObjectMapper objectMapper;
    protected final DeadLetterMessageService deadLetterMessageService;

    /**
     * 消费原始 topic 消息（从 Kafka Header 传播 correlation ID）。
     */
    protected void consumeRecord(ConsumerRecord<String, String> record, Acknowledgment ack) {
        CorrelationIdSupport.runWithContext(CorrelationIdSupport.contextFromKafka(record), () ->
                consumeMessage(record.topic(), record.key(), record.value(), ack));
    }

    /**
     * 消费重试 topic 消息（从 Kafka Header 传播 correlation ID）。
     */
    protected void consumeRetryRecord(ConsumerRecord<String, String> record, Acknowledgment ack) {
        CorrelationIdSupport.runWithContext(CorrelationIdSupport.contextFromKafka(record), () ->
                consumeRetryEnvelope(record.value(), ack));
    }

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
        }
        ack.acknowledge();
    }

    /**
     * 消费重试 topic 上的信封消息。
     */
    protected void consumeRetryEnvelope(String envelopeJson, Acknowledgment ack) {
        final KafkaRetryEnvelope envelope;
        try {
            envelope = objectMapper.readValue(envelopeJson, KafkaRetryEnvelope.class);
        } catch (Exception e) {
            log.error("[{}] Failed to consume retry envelope: {}", consumerName(), e.getMessage(), e);
            handleError("unknown", null, envelopeJson, MAX_RETRY_COUNT, e);
            ack.acknowledge();
            return;
        }

        if (envelope.deliverAfterEpochMs() > System.currentTimeMillis()) {
            awaitKafkaBroker(
                    KafkaTopicNames.retryTopic(envelope.sourceTopic()),
                    envelope.messageKey(),
                    envelopeJson);
            ack.acknowledge();
            return;
        }

        try {
            process(envelope.sourceTopic(), envelope.messageKey(), envelope.messageValue(), envelope.retryCount());
        } catch (Exception e) {
            handleError(
                    envelope.sourceTopic(),
                    envelope.messageKey(),
                    envelope.messageValue(),
                    envelope.retryCount(),
                    e);
        }
        ack.acknowledge();
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
            awaitKafkaBroker(KafkaTopicNames.retryTopic(sourceTopic), messageKey, json);
        } catch (Exception ex) {
            throw forwardingFailure("retry", sourceTopic, ex);
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
            awaitKafkaBroker(KafkaTopicNames.dlqTopic(sourceTopic), messageKey, json);
        } catch (Exception ex) {
            throw forwardingFailure("DLQ", sourceTopic, ex);
        }
    }

    private void awaitKafkaBroker(String topic, String messageKey, String payload) {
        try {
            var future = kafkaTemplate.send(topic, messageKey, payload);
            if (future == null) {
                throw new IllegalStateException("Kafka send returned no confirmation future");
            }
            future.get(FORWARD_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting Kafka confirmation", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("Kafka broker did not confirm forwarding to " + topic, failure);
        }
    }

    private IllegalStateException forwardingFailure(String destination, String sourceTopic, Exception cause) {
        log.error("[{}] Failed to publish {} message for topic={}: {}",
                consumerName(), destination, sourceTopic, cause.getMessage(), cause);
        return new IllegalStateException(
                "Failed to publish " + destination + " message for topic " + sourceTopic,
                cause);
    }
}
