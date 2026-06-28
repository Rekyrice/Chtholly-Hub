package com.chtholly.common.tracing;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Correlation ID 与 MDC 上下文工具。
 */
public final class CorrelationIdSupport {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_METHOD = "method";
    public static final String MDC_URI = "uri";
    public static final String DEFAULT_ID = "system";

    private CorrelationIdSupport() {
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    public static String resolve(String incomingHeader) {
        if (incomingHeader == null || incomingHeader.isBlank()) {
            return generate();
        }
        return incomingHeader.trim();
    }

    public static void putHttp(String correlationId, String method, String uri) {
        MDC.put(MDC_CORRELATION_ID, correlationId);
        MDC.put(MDC_METHOD, method);
        MDC.put(MDC_URI, uri);
    }

    public static Map<String, String> contextFromKafka(ConsumerRecord<?, ?> record) {
        String correlationId = extractKafkaHeader(record);
        String topic = record == null ? "unknown" : record.topic();
        return context(correlationId, "KAFKA", topic);
    }

    public static Map<String, String> context(String correlationId, String method, String uri) {
        Map<String, String> ctx = new HashMap<>(3);
        ctx.put(MDC_CORRELATION_ID, correlationId == null || correlationId.isBlank() ? generate() : correlationId);
        ctx.put(MDC_METHOD, method == null ? "" : method);
        ctx.put(MDC_URI, uri == null ? "" : uri);
        return ctx;
    }

    public static Map<String, String> copyContext() {
        Map<String, String> ctx = MDC.getCopyOfContextMap();
        return ctx == null ? Map.of() : Map.copyOf(ctx);
    }

    public static void setContext(Map<String, String> context) {
        MDC.clear();
        if (context != null && !context.isEmpty()) {
            MDC.setContextMap(context);
        }
    }

    public static void runWithContext(Map<String, String> context, Runnable action) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            setContext(context);
            action.run();
        } finally {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    private static String extractKafkaHeader(ConsumerRecord<?, ?> record) {
        if (record == null || record.headers() == null) {
            return generate();
        }
        Header header = record.headers().lastHeader(HEADER);
        if (header == null || header.value() == null) {
            return generate();
        }
        String value = new String(header.value(), StandardCharsets.UTF_8).trim();
        return value.isEmpty() ? generate() : value;
    }
}
