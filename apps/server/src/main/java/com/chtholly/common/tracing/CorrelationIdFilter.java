package com.chtholly.common.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 为每个 HTTP 请求注入 Correlation ID，写入 MDC 与响应头，并记录请求起止日志。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = CorrelationIdSupport.resolve(request.getHeader(CorrelationIdSupport.HEADER));
        String method = request.getMethod();
        String uri = request.getRequestURI();

        CorrelationIdSupport.putHttp(correlationId, method, uri);
        response.setHeader(CorrelationIdSupport.HEADER, correlationId);

        long start = System.currentTimeMillis();
        log.info("[{}] {} {} started", correlationId, method, uri);
        try {
            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            log.error("[{}] {} {} failed: {}", correlationId, method, uri, ex.getMessage());
            throw ex;
        } finally {
            long duration = System.currentTimeMillis() - start;
            log.info("[{}] {} {} completed in {}ms, status={}",
                    correlationId, method, uri, duration, response.getStatus());
            MDC.clear();
        }
    }
}
