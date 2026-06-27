package com.chtholly.agent.ws;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/** 每个 WebSocket session 的 chat 消息速率限制。 */
@Component
public class AgentSessionRateLimiter {

    static final int MAX_CHAT_MESSAGES = 10;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, Deque<Long>> sessionTimestamps = new ConcurrentHashMap<>();

    /** 尝试占用一次 chat 配额；超出窗口内上限返回 false。 */
    boolean tryAcquireChat(String sessionId) {
        long now = System.currentTimeMillis();
        Deque<Long> times = sessionTimestamps.computeIfAbsent(sessionId, id -> new ConcurrentLinkedDeque<>());
        synchronized (times) {
            long windowMs = WINDOW.toMillis();
            while (!times.isEmpty() && now - times.peekFirst() > windowMs) {
                times.pollFirst();
            }
            if (times.size() >= MAX_CHAT_MESSAGES) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    void removeSession(String sessionId) {
        sessionTimestamps.remove(sessionId);
    }
}
