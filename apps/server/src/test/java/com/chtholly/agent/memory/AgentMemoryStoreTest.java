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
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentMemoryStoreTest {

    private static final String CHAT_SESSION = "sess-test-abc";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ListOperations<String, String> listOps;
    @Mock
    private ValueOperations<String, String> valueOps;

    private AgentMemoryStore store;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AgentProperties properties = new AgentProperties();
        properties.setMemoryMaxTurns(20);
        properties.setMemoryTtlMinutes(120);
        store = new AgentMemoryStore(redis, objectMapper, properties);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.get(anyString())).thenReturn("generation-0");
        lenient().when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
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
                .contains("RPUSH", "LTRIM", "PEXPIRE", "TIME", "GET", "PEXPIRE', KEYS[3]")
                .doesNotContain("INCR");
        assertThat(keysCaptor.getValue().get(0)).isEqualTo("agent:memory:42:" + CHAT_SESSION);
        assertThat(keysCaptor.getValue())
                .contains("agent:memory:generation:42:" + CHAT_SESSION)
                .hasSize(3);
        assertThat(argsCaptor.getValue()[4]).isEqualTo("generation-0");
        assertThat(argsCaptor.getValue()[5].toString()).contains("\"role\":\"USER\"");
    }

    @Test
    void addTurnsAppendsExchangeInOneRedisCommand() {
        stubScriptResult(1L);

        assertThat(store.addTurns(42L, CHAT_SESSION, List.of(
                AgentTurn.user("question"),
                AgentTurn.assistant("answer")))).isTrue();

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(DefaultRedisScript.class), anyList(), argsCaptor.capture());
        assertThat(argsCaptor.getValue()[5].toString()).contains("\"role\":\"USER\"");
        assertThat(argsCaptor.getValue()[6].toString()).contains("\"role\":\"ASSISTANT\"");
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
    void clearMemoryAtomicallyRotatesExpiringGenerationAndDeletesMemoryWithoutDroppingTheActiveLease() {
        stubScriptResult(1L);

        store.clearMemory(7L, CHAT_SESSION);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DefaultRedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(DefaultRedisScript.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(scriptCaptor.capture(), keysCaptor.capture(), argsCaptor.capture());
        assertThat(scriptCaptor.getValue().getScriptAsString())
                .contains("EXISTS", "SET", "PX", "DEL")
                .doesNotContain("INCR");
        assertThat(keysCaptor.getValue())
                .containsExactly(
                        "agent:memory:7:" + CHAT_SESSION,
                        "agent:memory:generation:7:" + CHAT_SESSION,
                        com.chtholly.agent.runtime.AgentTurnKeySupport.activeKey(7L, CHAT_SESSION));
        assertThat(argsCaptor.getValue()[0].toString()).isNotBlank();
        assertThat(argsCaptor.getValue()[1]).isEqualTo("7200000");
        assertThat(scriptCaptor.getValue().getScriptAsString())
                .contains("KEYS[3]")
                .doesNotContain("'DEL', KEYS[3]");
    }

    @Test
    void clearMemoryAcceptsANoopForACompletelyUnknownSession() {
        stubScriptResult(0L);

        store.clearMemory(17L, "sess-unknown");

        verify(redis).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
        verify(valueOps, org.mockito.Mockito.never()).set(anyString(), anyString());
    }

    @Test
    void aNewSnapshotInitializesAnOpaqueGenerationWithTheExistingMemoryTtl() {
        when(redis.opsForList()).thenReturn(listOps);
        when(valueOps.get("agent:memory:generation:18:" + CHAT_SESSION)).thenReturn(null);
        when(listOps.range("agent:memory:18:" + CHAT_SESSION, 0, -1)).thenReturn(List.of());

        store.getOrCreateMemory(18L, CHAT_SESSION);

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).setIfAbsent(
                org.mockito.ArgumentMatchers.eq("agent:memory:generation:18:" + CHAT_SESSION),
                tokenCaptor.capture(),
                ttlCaptor.capture());
        assertThat(tokenCaptor.getValue()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofMinutes(120));
    }

    @Test
    void oldSnapshotCannotWriteAfterSessionWasCleared() {
        when(redis.opsForList()).thenReturn(listOps);
        when(valueOps.get("agent:memory:generation:7:" + CHAT_SESSION)).thenReturn("generation-old");
        when(listOps.range("agent:memory:7:" + CHAT_SESSION, 0, -1)).thenReturn(List.of());
        doReturn(1L, -3L).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
        AgentConversationMemory oldSnapshot = store.getOrCreateMemory(7L, CHAT_SESSION);

        store.clearMemory(7L, CHAT_SESSION);
        AgentMemoryStore.MemoryWriteResult result = oldSnapshot.addExchange(
                AgentTurn.user("stale question"),
                AgentTurn.assistant("stale answer"),
                null);

        assertThat(result.status()).isEqualTo(AgentMemoryStore.MemoryWriteStatus.REJECTED);
        assertThat(result.failureCode()).isEqualTo("SESSION_CLEARED");
        assertThat(oldSnapshot.isEmpty()).isTrue();
    }

    @Test
    void oldSnapshotSingleTurnIsNotAppendedLocallyAfterSessionWasCleared() {
        when(redis.opsForList()).thenReturn(listOps);
        when(valueOps.get("agent:memory:generation:8:" + CHAT_SESSION)).thenReturn("generation-old");
        when(listOps.range("agent:memory:8:" + CHAT_SESSION, 0, -1)).thenReturn(List.of());
        doReturn(1L, -3L).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
        AgentConversationMemory oldSnapshot = store.getOrCreateMemory(8L, CHAT_SESSION);

        store.clearMemory(8L, CHAT_SESSION);
        oldSnapshot.add(AgentTurn.user("stale single turn"));

        assertThat(oldSnapshot.isEmpty()).isTrue();
    }

    @Test
    void sessionClearedRejectionInvalidatesTheStaleLocalSnapshot() throws Exception {
        when(redis.opsForList()).thenReturn(listOps);
        String generationKey = "agent:memory:generation:12:" + CHAT_SESSION;
        String oldJson = objectMapper.writeValueAsString(AgentTurn.assistant("old history"));
        when(valueOps.get(generationKey)).thenReturn("generation-old");
        when(listOps.range("agent:memory:12:" + CHAT_SESSION, 0, -1)).thenReturn(List.of(oldJson));
        doReturn(-3L).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
        AgentConversationMemory oldSnapshot = store.getOrCreateMemory(12L, CHAT_SESSION);

        AgentMemoryStore.MemoryWriteResult result = oldSnapshot.addExchange(
                AgentTurn.user("stale question"),
                AgentTurn.assistant("stale answer"),
                null);

        assertThat(result.failureCode()).isEqualTo("SESSION_CLEARED");
        assertThat(store.getStats().activeSessions()).isZero();
    }

    @Test
    void anExpiredGenerationRejectsAnOldSnapshotWithoutAReplacementClear() {
        when(redis.opsForList()).thenReturn(listOps);
        String generationKey = "agent:memory:generation:16:" + CHAT_SESSION;
        when(valueOps.get(generationKey)).thenReturn("generation-old");
        when(listOps.range("agent:memory:16:" + CHAT_SESSION, 0, -1)).thenReturn(List.of());
        doReturn(-3L).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
        AgentConversationMemory oldSnapshot = store.getOrCreateMemory(16L, CHAT_SESSION);

        AgentMemoryStore.MemoryWriteResult result = oldSnapshot.addExchange(
                AgentTurn.user("expired generation question"),
                AgentTurn.assistant("must not persist"),
                null);

        assertThat(result.failureCode()).isEqualTo("SESSION_CLEARED");
        assertThat(oldSnapshot.isEmpty()).isTrue();
    }

    @Test
    void aNewSnapshotLoadedAfterClearCanWriteWithTheRotatedGeneration() {
        when(redis.opsForList()).thenReturn(listOps);
        String generationKey = "agent:memory:generation:13:" + CHAT_SESSION;
        when(valueOps.get(generationKey)).thenReturn("generation-new");
        when(listOps.range("agent:memory:13:" + CHAT_SESSION, 0, -1)).thenReturn(List.of());
        doReturn(1L, 1L).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));

        store.clearMemory(13L, CHAT_SESSION);
        AgentConversationMemory newSnapshot = store.getOrCreateMemory(13L, CHAT_SESSION);
        AgentMemoryStore.MemoryWriteResult result = newSnapshot.addExchange(
                AgentTurn.user("new question"),
                AgentTurn.assistant("new answer"),
                null);

        assertThat(result.committed()).isTrue();
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis, org.mockito.Mockito.times(2))
                .execute(any(DefaultRedisScript.class), anyList(), argsCaptor.capture());
        assertThat(argsCaptor.getAllValues().getLast()[4]).isEqualTo("generation-new");
    }

    @Test
    void anotherStoreReloadsItsHotCacheAfterGenerationChanges() throws Exception {
        when(redis.opsForList()).thenReturn(listOps);
        String redisKey = "agent:memory:9:" + CHAT_SESSION;
        String generationKey = "agent:memory:generation:9:" + CHAT_SESSION;
        String oldJson = objectMapper.writeValueAsString(AgentTurn.assistant("old history"));
        when(valueOps.get(generationKey)).thenReturn("generation-old", "generation-new");
        when(listOps.range(redisKey, 0, -1)).thenReturn(List.of(oldJson), List.of());
        stubScriptResult(1L);
        AgentProperties properties = new AgentProperties();
        properties.setMemoryMaxTurns(20);
        properties.setMemoryTtlMinutes(120);
        AgentMemoryStore anotherStore = new AgentMemoryStore(redis, objectMapper, properties);

        assertThat(anotherStore.getTurns(9L, CHAT_SESSION)).hasSize(1);
        store.clearMemory(9L, CHAT_SESSION);

        assertThat(anotherStore.getTurns(9L, CHAT_SESSION)).isEmpty();
        verify(listOps, org.mockito.Mockito.times(2)).range(redisKey, 0, -1);
    }

    @Test
    void clearMemoryFailsClosedWhenRedisCannotConfirmTheGeneration() throws Exception {
        when(redis.opsForList()).thenReturn(listOps);
        String redisKey = "agent:memory:11:" + CHAT_SESSION;
        String generationKey = "agent:memory:generation:11:" + CHAT_SESSION;
        String oldJson = objectMapper.writeValueAsString(AgentTurn.assistant("old history"));
        when(valueOps.get(generationKey)).thenReturn("generation-old");
        when(listOps.range(redisKey, 0, -1)).thenReturn(List.of(oldJson));
        assertThat(store.getTurns(11L, CHAT_SESSION)).hasSize(1);
        doThrow(new IllegalStateException("redis unavailable")).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));

        assertThatThrownBy(() -> store.clearMemory(11L, CHAT_SESSION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis unavailable");
    }

    @Test
    void clearMemoryFailsClosedWhenRedisReturnsNoResult() {
        doReturn(null).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));

        assertThatThrownBy(() -> store.clearMemory(14L, CHAT_SESSION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outcome is unknown");
    }

    @Test
    void clearMemoryFailsClosedWhenRedisReturnsAnInvalidResult() {
        stubScriptResult(2L);

        assertThatThrownBy(() -> store.clearMemory(15L, CHAT_SESSION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outcome is unknown");
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
