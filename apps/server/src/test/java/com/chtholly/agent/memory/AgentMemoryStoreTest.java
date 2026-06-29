package com.chtholly.agent.memory;

import com.chtholly.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentMemoryStoreTest {

    private static final String CHAT_SESSION = "sess-test-abc";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ListOperations<String, String> listOps;

    private AgentMemoryStore store;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AgentProperties properties = new AgentProperties();
        properties.setMemoryMaxTurns(20);
        properties.setMemoryTtlMinutes(120);
        store = new AgentMemoryStore(redis, objectMapper, properties);
    }

    @Test
    void addTurnUsesRpushLtrimAndExpire() throws Exception {
        when(redis.opsForList()).thenReturn(listOps);
        AgentTurn turn = AgentTurn.user("hello");

        store.addTurn(42L, CHAT_SESSION, turn);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(listOps).rightPush(eq("agent:memory:42:" + CHAT_SESSION), jsonCaptor.capture());
        assertThat(jsonCaptor.getValue()).contains("\"role\":\"USER\"");

        verify(listOps).trim("agent:memory:42:" + CHAT_SESSION, -20, -1);
        verify(redis).expire("agent:memory:42:" + CHAT_SESSION, Duration.ofMinutes(120));
    }

    @Test
    void clearMemoryDeletesRedisKey() {
        store.clearMemory(7L, CHAT_SESSION);
        verify(redis).delete("agent:memory:7:" + CHAT_SESSION);
    }

    @Test
    void getOrCreateMemoryLoadsFromRedisList() throws Exception {
        when(redis.opsForList()).thenReturn(listOps);
        String json = objectMapper.writeValueAsString(AgentTurn.assistant("hi"));
        when(listOps.range("agent:memory:3:" + CHAT_SESSION, 0, -1)).thenReturn(List.of(json));

        AgentConversationMemory memory = store.getOrCreateMemory(3L, CHAT_SESSION);

        assertThat(memory.isEmpty()).isFalse();
        assertThat(memory.formatForPrompt()).contains("Assistant: hi");
        verify(redis).expire("agent:memory:3:" + CHAT_SESSION, Duration.ofMinutes(120));
    }

    @Test
    void getStatsReflectsCachedSessions() throws Exception {
        when(redis.opsForList()).thenReturn(listOps);
        String json = objectMapper.writeValueAsString(AgentTurn.assistant("hi"));
        when(listOps.range("agent:memory:1:sess-a", 0, -1)).thenReturn(List.of(json));
        when(listOps.range("agent:memory:2:sess-b", 0, -1)).thenReturn(List.of(json, json));

        store.getOrCreateMemory(1L, "sess-a");
        store.getOrCreateMemory(2L, "sess-b");

        AgentMemoryStats stats = store.getStats();
        assertThat(stats.activeSessions()).isEqualTo(2);
        assertThat(stats.totalTurns()).isEqualTo(3);
    }
}
