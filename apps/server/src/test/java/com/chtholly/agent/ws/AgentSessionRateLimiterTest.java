package com.chtholly.agent.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSessionRateLimiterTest {

    private AgentSessionRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new AgentSessionRateLimiter();
    }

    @Test
    void allowsTenChatMessagesThenRateLimits() {
        String sessionId = "session-1";
        for (int i = 0; i < AgentSessionRateLimiter.MAX_CHAT_MESSAGES; i++) {
            assertThat(limiter.tryAcquireChat(sessionId)).isTrue();
        }
        assertThat(limiter.tryAcquireChat(sessionId)).isFalse();
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquireChat(sessionId)).isFalse();
        }
    }

    @Test
    void sessionsAreIsolated() {
        assertThat(limiter.tryAcquireChat("a")).isTrue();
        assertThat(limiter.tryAcquireChat("b")).isTrue();
    }
}
