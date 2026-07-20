package com.chtholly.common.kafka;

import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

import org.awaitility.Awaitility;
import org.springframework.kafka.support.SendResult;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbstractKafkaConsumerTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private DeadLetterMessageService deadLetterMessageService;

    private TestConsumer consumer;
    private Acknowledgment ack;

    @BeforeEach
    void setUp() {
        consumer = new TestConsumer(kafkaTemplate, new ObjectMapper(), deadLetterMessageService);
        ack = mock(Acknowledgment.class);
    }

    @Test
    void consumeRecordPropagatesKafkaCorrelationHeader() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("counter-events", 0, 0L, null, "{}");
        record.headers().add(new RecordHeader(
                "X-Correlation-Id",
                "kafka-trace".getBytes(StandardCharsets.UTF_8)));
        consumer.captureMdc = true;

        consumer.consumeRecord(record, ack);

        assertEquals("kafka-trace", consumer.lastCorrelationId);
        verify(ack).acknowledge();
    }

    @Test
    void acksAfterSuccessfulProcess() {
        consumer.consumeMessage("counter-events", null, "{}", ack);
        verify(ack).acknowledge();
        verify(deadLetterMessageService, never()).recordFailure(anyString(), any(), anyString(), any(), anyInt(), any());
    }

    @Test
    void recordsDeadLetterAndSendsRetryOnFailure() throws Exception {
        consumer.failNext = true;
        when(kafkaTemplate.send(eq("counter-events-retry"), eq(null), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        consumer.consumeMessage("counter-events", null, "{}", ack);

        verify(deadLetterMessageService).recordFailure(
                eq("counter-events"), eq(null), eq("{}"), any(RuntimeException.class), eq(0), eq(DeadLetterStatus.RETRYING));
        verify(kafkaTemplate).send(eq("counter-events-retry"), eq(null), anyString());
        verify(ack).acknowledge();
    }

    @Test
    void sendsToDlqWhenRetryCountReached() throws Exception {
        consumer.failNext = true;
        when(kafkaTemplate.send(eq("counter-events-dlq"), eq(null), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        consumer.consumeMessage("counter-events", null, "{}", AbstractKafkaConsumer.MAX_RETRY_COUNT, ack);

        verify(deadLetterMessageService).recordFailure(
                eq("counter-events"), eq(null), eq("{}"), any(RuntimeException.class),
                eq(AbstractKafkaConsumer.MAX_RETRY_COUNT), eq(DeadLetterStatus.DEAD));
        verify(kafkaTemplate).send(eq("counter-events-dlq"), eq(null), anyString());
        verify(kafkaTemplate, never()).send(eq("counter-events-retry"), eq(null), anyString());
        verify(ack).acknowledge();
    }

    @Test
    void doesNotAckWhenRetryPublishIsNotBrokerConfirmed() {
        consumer.failNext = true;
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send(eq("counter-events-retry"), eq(null), anyString())).thenReturn(failed);

        assertThatThrownBy(() -> consumer.consumeMessage("counter-events", null, "{}", ack))
                .hasRootCauseMessage("broker unavailable");

        verify(ack, never()).acknowledge();
    }

    @Test
    void waitsForRetryBrokerConfirmationBeforeAckingSource() throws Exception {
        consumer.failNext = true;
        CompletableFuture<SendResult<String, String>> brokerConfirmation = new CompletableFuture<>();
        when(kafkaTemplate.send(eq("counter-events-retry"), eq(null), anyString()))
                .thenReturn(brokerConfirmation);

        CompletableFuture<Void> processing = CompletableFuture.runAsync(
                () -> consumer.consumeMessage("counter-events", null, "{}", ack));
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                verify(kafkaTemplate).send(eq("counter-events-retry"), eq(null), anyString()));
        verify(ack, never()).acknowledge();

        brokerConfirmation.complete(null);
        processing.get(2, TimeUnit.SECONDS);
        verify(ack).acknowledge();
    }

    @Test
    void doesNotAckWhenDlqPublishIsNotBrokerConfirmed() {
        consumer.failNext = true;
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send(eq("counter-events-dlq"), eq(null), anyString())).thenReturn(failed);

        assertThatThrownBy(() -> consumer.consumeMessage(
                "counter-events", null, "{}", AbstractKafkaConsumer.MAX_RETRY_COUNT, ack))
                .hasRootCauseMessage("broker unavailable");

        verify(ack, never()).acknowledge();
    }

    @Test
    void deferredRetryPublishFailureLeavesRetryOffsetUnacknowledged() throws Exception {
        String envelope = new ObjectMapper().writeValueAsString(new KafkaRetryEnvelope(
                "counter-events", "event-1", "{}", 1, System.currentTimeMillis() + 60_000));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send("counter-events-retry", "event-1", envelope)).thenReturn(failed);

        assertThatThrownBy(() -> consumer.consumeRetryEnvelope(envelope, ack))
                .hasRootCauseMessage("broker unavailable");

        verify(ack, never()).acknowledge();
    }

    private static class TestConsumer extends AbstractKafkaConsumer {
        private boolean failNext;
        private boolean captureMdc;
        private String lastCorrelationId;

        TestConsumer(KafkaTemplate<String, String> kafkaTemplate,
                     ObjectMapper objectMapper,
                     DeadLetterMessageService deadLetterMessageService) {
            super(kafkaTemplate, objectMapper, deadLetterMessageService);
        }

        @Override
        protected void process(String sourceTopic, String messageKey, String payload, int retryCount) {
            if (captureMdc) {
                lastCorrelationId = MDC.get("correlationId");
            }
            if (failNext) {
                throw new RuntimeException("boom");
            }
        }

        @Override
        protected String consumerName() {
            return "test-consumer";
        }
    }
}
