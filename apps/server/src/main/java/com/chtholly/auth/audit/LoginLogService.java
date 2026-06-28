package com.chtholly.auth.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class LoginLogService {

    private final LoginLogMapper loginLogMapper;

    /**
     * 记录一次登录/注册成功事件。
     */
    @Transactional
    public void recordSuccess(Long userId, String identifier, String channel, String ip, String userAgent) {
        record(userId, identifier, channel, ip, userAgent, "SUCCESS", null);
    }

    /**
     * 记录一次登录/注册失败事件。
     */
    @Transactional
    public void recordFailure(Long userId, String identifier, String channel, String ip, String userAgent,
                              LoginFailureReason reason) {
        record(userId, identifier, channel, ip, userAgent, "FAILED",
                reason != null ? reason.name() : null);
    }

    /**
     * 记录一次登录/注册事件（兼容旧调用）。
     */
    @Transactional
    public void record(Long userId, String identifier, String channel, String ip, String userAgent, String status) {
        record(userId, identifier, channel, ip, userAgent, status, null);
    }

    private void record(Long userId, String identifier, String channel, String ip, String userAgent,
                        String status, String failureReason) {
        LoginLog log = LoginLog.builder()
                .userId(userId)
                .identifier(identifier)
                .channel(channel)
                .ip(ip)
                .userAgent(userAgent)
                .status(status)
                .failureReason(failureReason)
                .createdAt(Instant.now())
                .build();
        loginLogMapper.insert(log);
    }
}
