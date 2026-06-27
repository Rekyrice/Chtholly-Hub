package com.chtholly.auth.security;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败计数与账户/IP 锁定守卫。
 * <p>
 * 同一 identifier 连续失败 5 次锁定 15 分钟；同一 IP 连续失败 20 次锁定 30 分钟。
 */
@Component
@RequiredArgsConstructor
public class LoginFailureGuard {

    private static final String FAIL_PREFIX = "login:fail:";
    private static final String LOCK_PREFIX = "login:lock:";
    private static final String IP_FAIL_PREFIX = "login:fail:ip:";
    private static final String IP_LOCK_PREFIX = "login:lock:ip:";

    private static final int IDENTIFIER_MAX_FAILS = 5;
    private static final Duration IDENTIFIER_FAIL_TTL = Duration.ofMinutes(30);
    private static final Duration IDENTIFIER_LOCK_TTL = Duration.ofSeconds(900);
    private static final int IP_MAX_FAILS = 20;
    private static final Duration IP_FAIL_TTL = Duration.ofMinutes(30);
    private static final Duration IP_LOCK_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    /**
     * 若 identifier 或 IP 处于锁定期，抛出 423 ACCOUNT_LOCKED。
     */
    public void assertNotLocked(String identifier, String ip) {
        if (ip != null && !ip.isBlank() && Boolean.TRUE.equals(redisTemplate.hasKey(IP_LOCK_PREFIX + ip))) {
            throw lockedException();
        }
        if (Boolean.TRUE.equals(redisTemplate.hasKey(LOCK_PREFIX + identifier))) {
            throw lockedException();
        }
    }

    /**
     * 登录成功后清除 identifier 的失败计数与锁定状态。
     */
    public void onSuccess(String identifier) {
        redisTemplate.delete(FAIL_PREFIX + identifier);
        redisTemplate.delete(LOCK_PREFIX + identifier);
    }

    /**
     * 记录一次登录失败，并在达到阈值时设置锁定键。
     */
    public void onFailure(String identifier, String ip) {
        long idFails = increment(FAIL_PREFIX + identifier, IDENTIFIER_FAIL_TTL);
        if (idFails >= IDENTIFIER_MAX_FAILS) {
            redisTemplate.opsForValue().set(LOCK_PREFIX + identifier, "1", IDENTIFIER_LOCK_TTL);
        }
        if (ip != null && !ip.isBlank()) {
            long ipFails = increment(IP_FAIL_PREFIX + ip, IP_FAIL_TTL);
            if (ipFails >= IP_MAX_FAILS) {
                redisTemplate.opsForValue().set(IP_LOCK_PREFIX + ip, "1", IP_LOCK_TTL);
            }
        }
    }

    private long increment(String key, Duration ttl) {
        Long value = redisTemplate.opsForValue().increment(key);
        if (value != null && value == 1L) {
            redisTemplate.expire(key, ttl);
        }
        return value != null ? value : 0L;
    }

    private BusinessException lockedException() {
        return new BusinessException(
                ErrorCode.ACCOUNT_LOCKED,
                ErrorCode.ACCOUNT_LOCKED.getDefaultMessage(),
                HttpStatus.LOCKED.value());
    }
}
