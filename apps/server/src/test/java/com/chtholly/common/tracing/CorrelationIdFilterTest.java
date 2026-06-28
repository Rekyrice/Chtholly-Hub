package com.chtholly.common.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void reusesIncomingHeaderAndReturnsInResponse() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/posts/feed");
        request.addHeader(CorrelationIdSupport.HEADER, "upstream-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcInChain.set(MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID)));

        assertEquals("upstream-id", response.getHeader(CorrelationIdSupport.HEADER));
        assertEquals("upstream-id", mdcInChain.get());
        assertNull(MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID));
    }

    @Test
    void generatesCorrelationIdWhenHeaderMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertNotNull(response.getHeader(CorrelationIdSupport.HEADER));
    }

    @Test
    void clearsMdcEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fail");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(RuntimeException.class, () -> filter.doFilter(request, response, (req, res) -> {
            MDC.put("temp", "1");
            throw new RuntimeException("boom");
        }));

        assertNull(MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID));
    }
}
