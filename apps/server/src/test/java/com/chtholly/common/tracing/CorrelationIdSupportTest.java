package com.chtholly.common.tracing;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CorrelationIdSupportTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void resolve_reusesIncomingHeaderOrGenerates() {
        assertEquals("abc-123", CorrelationIdSupport.resolve("abc-123"));
        assertNotEquals("", CorrelationIdSupport.resolve(null));
        assertNotEquals("", CorrelationIdSupport.resolve("  "));
    }

    @Test
    void contextFromKafka_readsHeader() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("counter-events", 0, 0L, "k", "{}");
        record.headers().add(new RecordHeader(
                CorrelationIdSupport.HEADER,
                "kafka-corr".getBytes(StandardCharsets.UTF_8)));

        Map<String, String> ctx = CorrelationIdSupport.contextFromKafka(record);

        assertEquals("kafka-corr", ctx.get(CorrelationIdSupport.MDC_CORRELATION_ID));
        assertEquals("KAFKA", ctx.get(CorrelationIdSupport.MDC_METHOD));
        assertEquals("counter-events", ctx.get(CorrelationIdSupport.MDC_URI));
    }

    @Test
    void runWithContext_restoresPreviousMdc() {
        MDC.put(CorrelationIdSupport.MDC_CORRELATION_ID, "parent");
        AtomicReference<String> inside = new AtomicReference<>();

        CorrelationIdSupport.runWithContext(
                CorrelationIdSupport.context("child", "ASYNC", "/task"),
                () -> inside.set(MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID)));

        assertEquals("child", inside.get());
        assertEquals("parent", MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID));
    }

    @Test
    void copyContext_returnsEmptyWhenMdcEmpty() {
        assertEquals(Map.of(), CorrelationIdSupport.copyContext());
        CorrelationIdSupport.putHttp("x", "GET", "/api");
        assertEquals("x", CorrelationIdSupport.copyContext().get(CorrelationIdSupport.MDC_CORRELATION_ID));
        MDC.clear();
        assertNull(MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID));
    }
}
