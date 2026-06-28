package com.chtholly.common.tracing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void copiesMdcIntoAsyncThread() throws Exception {
        CorrelationIdSupport.putHttp("async-parent", "GET", "/api/v1/notifications");
        AtomicReference<String> asyncId = new AtomicReference<>();

        Thread worker = new Thread(decorator.decorate(() ->
                asyncId.set(MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID))));
        worker.start();
        worker.join();

        assertEquals("async-parent", asyncId.get());
        assertEquals("async-parent", MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID));
    }

    @Test
    void restoresPreviousMdcAfterTask() {
        MDC.put(CorrelationIdSupport.MDC_CORRELATION_ID, "main");

        decorator.decorate(() -> MDC.put(CorrelationIdSupport.MDC_CORRELATION_ID, "worker")).run();

        assertEquals("main", MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID));
        MDC.clear();
        assertNull(MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID));
    }
}
