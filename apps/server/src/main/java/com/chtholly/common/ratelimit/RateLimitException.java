package com.chtholly.common.ratelimit;

import lombok.Getter;

/** Thrown when an API rate limit is exceeded; mapped to HTTP 429 by {@link com.chtholly.common.web.GlobalExceptionHandler}. */
@Getter
public class RateLimitException extends RuntimeException {

    private final int retryAfterSeconds;

    public RateLimitException(int retryAfterSeconds) {
        super("请求过于频繁，请稍后再试");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
}
