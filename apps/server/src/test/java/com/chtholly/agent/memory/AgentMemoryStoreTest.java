package com.chtholly.agent.memory;

import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.runtime.AgentTurnControl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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
    void addTurnUsesOneAtomicAppendTrimAndExpireScript() {
        stubScriptResult(1L);
        AgentTurn turn = AgentTurn.user("hello");

        store.addTurn(42L, CHAT_SESSION, turn);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DefaultRedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(DefaultRedisScript.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(scriptCaptor.capture(), keysCaptor.capture(), argsCaptor.capture());
        assertThat(scriptCaptor.getValue().getScriptAsString())
                .contains("RPUSH", "LTRIM", "PEXPIRE", "TIME", "GET");
        assertThat(keysCaptor.getValue().get(0)).isEqualTo("agent:memory:42:" + CHAT_SESSION);
        assertThat(argsCaptor.getValue()[4].toString()).contains("\"role\":\"USER\"");
    }

    @Test
    void addTurnsAppendsExchangeInOneRedisCommand() {
        stubScriptResult(1L);

        assertThat(store.addTurns(42L, CHAT_SESSION, List.of(
                AgentTurn.user("question"),
                AgentTurn.assistant("answer")))).isTrue();

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(DefaultRedisScript.class), anyList(), argsCaptor.capture());
        assertThat(argsCaptor.getValue()[4].toString()).contains("\"role\":\"USER\"");
        assertThat(argsCaptor.getValue()[5].toString()).contains("\"role\":\"ASSISTANT\"");
    }

    @Test
    void fencedExchangeRejectsAStaleTurn() {
        stubScriptResult(-2L);
        AgentTurnControl control = AgentTurnControl.create(
                "request-1", "turn-1", CHAT_SESSION, "connection-1", Duration.ofSeconds(30));

        AgentMemoryStore.MemoryWriteResult result = store.addTurns(
                42L,
                CHAT_SESSION,
                List.of(AgentTurn.user("question"), AgentTurn.assistant("answer")),
                control);

        assertThat(result.status()).isEqualTo(AgentMemoryStore.MemoryWriteStatus.REJECTED);
        assertThat(result.failureCode()).isEqualTo("STALE_TURN");
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(DefaultRedisScript.class), anyList(), argsCaptor.capture());
        assertThat(argsCaptor.getValue()[3]).isEqualTo("turn-1");
    }

    @Test
    void transportFailureReturnsUnknownOutcome() {
        doThrow(new IllegalStateException("redis unavailable"))
                .when(redis).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));

        AgentMemoryStore.MemoryWriteResult result = store.addTurns(
                42L,
                CHAT_SESSION,
                List.of(AgentTurn.user("question"), AgentTurn.assistant("answer")),
                null);

        assertThat(result.status()).isEqualTo(AgentMemoryStore.MemoryWriteStatus.UNKNOWN);
        assertThat(result.failureCode()).isEqualTo("REDIS_UNAVAILABLE");
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

    @SuppressWarnings("unchecked")
    private void stubScriptResult(long result) {
        doReturn(result).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }
}
