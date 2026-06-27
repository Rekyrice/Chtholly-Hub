package com.chtholly.common.web;

import java.util.LinkedHashMap;
import java.util.Map;

/** 统一 API 错误响应体：{ code, message, details }。 */
public final class ApiErrorBody {

    private ApiErrorBody() {
    }

    public static Map<String, Object> of(String code, String message) {
        return of(code, message, Map.of());
    }

    public static Map<String, Object> of(String code, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("details", details != null ? details : Map.of());
        return body;
    }

    /** 429 限流响应体，含 retryAfterSeconds。 */
    public static Map<String, Object> rateLimited(String message, int retryAfterSeconds) {
        Map<String, Object> body = of("RATE_LIMITED", message);
        body.put("retryAfterSeconds", retryAfterSeconds);
        return body;
    }
}
