package com.chtholly.common.kafka;

import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

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
    void acksAfterSuccessfulProcess() {
        consumer.consumeMessage("counter-events", null, "{}", ack);
        verify(ack).acknowledge();
        verify(deadLetterMessageService, never()).recordFailure(anyString(), any(), anyString(), any(), anyInt(), any());
    }

    @Test
    void recordsDeadLetterAndSendsRetryOnFailure() throws Exception {
        consumer.failNext = true;
        when(kafkaTemplate.send(eq("counter-events-retry"), eq(null), anyString())).thenReturn(null);

        consumer.consumeMessage("counter-events", null, "{}", ack);

        verify(deadLetterMessageService).recordFailure(
                eq("counter-events"), eq(null), eq("{}"), any(RuntimeException.class), eq(0), eq(DeadLetterStatus.RETRYING));
        verify(kafkaTemplate).send(eq("counter-events-retry"), eq(null), anyString());
        verify(ack).acknowledge();
    }

    @Test
    void sendsToDlqWhenRetryCountReached() throws Exception {
        consumer.failNext = true;
        when(kafkaTemplate.send(eq("counter-events-dlq"), eq(null), anyString())).thenReturn(null);

        consumer.consumeMessage("counter-events", null, "{}", AbstractKafkaConsumer.MAX_RETRY_COUNT, ack);

        verify(deadLetterMessageService).recordFailure(
                eq("counter-events"), eq(null), eq("{}"), any(RuntimeException.class),
                eq(AbstractKafkaConsumer.MAX_RETRY_COUNT), eq(DeadLetterStatus.DEAD));
        verify(kafkaTemplate).send(eq("counter-events-dlq"), eq(null), anyString());
        verify(kafkaTemplate, never()).send(eq("counter-events-retry"), eq(null), anyString());
        verify(ack).acknowledge();
    }

    private static class TestConsumer extends AbstractKafkaConsumer {
        private boolean failNext;

        TestConsumer(KafkaTemplate<String, String> kafkaTemplate,
                     ObjectMapper objectMapper,
                     DeadLetterMessageService deadLetterMessageService) {
            super(kafkaTemplate, objectMapper, deadLetterMessageService);
        }

        @Override
        protected void process(String sourceTopic, String messageKey, String payload, int retryCount) {
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
