package com.chtholly.comment.service;

import com.chtholly.comment.config.CommentProperties;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 评论发表速率限制（Redis 计数，窗口 60 秒）。 */
@Component
@RequiredArgsConstructor
public class CommentRateLimiter {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final CommentProperties commentProperties;

    /** 检查配额并递增；超限抛出 429 业务异常。 */
    public void checkAndIncrement(long userId) {
        String key = "comment:rate:" + userId;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, WINDOW);
        }
        if (count != null && count > commentProperties.getRateLimitPerMinute()) {
            throw new BusinessException(ErrorCode.COMMENT_RATE_LIMIT, "评论过于频繁，请稍后再试",
                    HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }
}
