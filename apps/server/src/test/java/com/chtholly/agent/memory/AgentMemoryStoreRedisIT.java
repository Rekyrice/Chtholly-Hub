package com.chtholly.agent.memory;

import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.runtime.AgentTurnControl;
import com.chtholly.agent.ws.AgentTurnCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AgentMemoryStoreRedisIT {

    private static final String SESSION_ID = "sess-generation-it";
    private static final long USER_ID = 91L;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory lettuce;
    private static StringRedisTemplate redis;

    private AgentMemoryStore firstStore;
    private AgentMemoryStore secondStore;

    @BeforeAll
    static void connectRedis() {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        lettuce = new LettuceConnectionFactory(standalone);
        lettuce.afterPropertiesSet();
        redis = new StringRedisTemplate(lettuce);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnectRedis() {
        if (lettuce != null) {
            lettuce.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        try (RedisConnection connection = lettuce.getConnection()) {
            connection.serverCommands().flushAll();
        }
        AgentProperties properties = new AgentProperties();
        properties.setMemoryMaxTurns(20);
        properties.setMemoryTtlMinutes(120);
        ObjectMapper objectMapper = new ObjectMapper();
        firstStore = new AgentMemoryStore(redis, objectMapper, properties);
        secondStore = new AgentMemoryStore(redis, objectMapper, properties);
    }

    @Test
    void clearOrdersBothSidesOfAppendAndAllowsOnlyANewSnapshotToWrite() {
        AgentConversationMemory oldSnapshot = secondStore.getOrCreateMemory(USER_ID, SESSION_ID);
        assertThat(oldSnapshot.addExchange(
                AgentTurn.user("before clear"),
                AgentTurn.assistant("old answer"))).isTrue();
        assertThat(redis.opsForList().size(memoryKey())).isEqualTo(2L);
        assertThat(secondStore.getTurns(USER_ID, SESSION_ID)).hasSize(2);
        String generationBeforeClear = redis.opsForValue().get(generationKey());
        assertThat(generationBeforeClear).isNotBlank();

        firstStore.clearMemory(USER_ID, SESSION_ID);

        assertThat(redis.hasKey(memoryKey())).isFalse();
        String generationAfterClear = redis.opsForValue().get(generationKey());
        assertThat(generationAfterClear).isNotBlank();
        assertThat(generationAfterClear).isNotEqualTo(generationBeforeClear);
        assertThat(redis.getExpire(generationKey(), TimeUnit.MILLISECONDS)).isPositive();
        AgentMemoryStore.MemoryWriteResult staleResult = oldSnapshot.addExchange(
                AgentTurn.user("after clear from stale snapshot"),
                AgentTurn.assistant("must not return"),
                null);
        assertThat(staleResult.failureCode()).isEqualTo("SESSION_CLEARED");
        assertThat(redis.hasKey(memoryKey())).isFalse();
        assertThat(secondStore.getTurns(USER_ID, SESSION_ID)).isEmpty();

        AgentConversationMemory newSnapshot = secondStore.getOrCreateMemory(USER_ID, SESSION_ID);
        assertThat(newSnapshot.addExchange(
                AgentTurn.user("after clear from new snapshot"),
                AgentTurn.assistant("new answer"))).isTrue();
        assertThat(redis.opsForList().size(memoryKey())).isEqualTo(2L);
    }

    @Test
    void clearingACompletelyUnknownSessionDoesNotCreateATombstone() {
        String unknownSession = "sess-random-unknown";

        firstStore.clearMemory(USER_ID, unknownSession);

        assertThat(redis.hasKey("agent:memory:" + USER_ID + ":" + unknownSession)).isFalse();
        assertThat(redis.hasKey("agent:memory:generation:" + USER_ID + ":" + unknownSession))
                .isFalse();
    }

    @Test
    void anExpiredGenerationRejectsTheOldSnapshotAndANewSnapshotGetsANewToken() {
        AgentConversationMemory oldSnapshot = firstStore.getOrCreateMemory(USER_ID, SESSION_ID);
        String oldGeneration = redis.opsForValue().get(generationKey());
        assertThat(oldGeneration).isNotBlank();
        assertThat(redis.delete(generationKey())).isTrue();

        AgentMemoryStore.MemoryWriteResult staleResult = oldSnapshot.addExchange(
                AgentTurn.user("expired token question"),
                AgentTurn.assistant("must not persist"),
                null);

        assertThat(staleResult.failureCode()).isEqualTo("SESSION_CLEARED");
        assertThat(redis.hasKey(memoryKey())).isFalse();
        AgentConversationMemory newSnapshot = secondStore.getOrCreateMemory(USER_ID, SESSION_ID);
        String newGeneration = redis.opsForValue().get(generationKey());
        assertThat(newGeneration).isNotBlank().isNotEqualTo(oldGeneration);
        assertThat(newSnapshot.addExchange(
                AgentTurn.user("new token question"),
                AgentTurn.assistant("new token answer"))).isTrue();
    }

    @Test
    void appendRefreshesTheGenerationTtlAlongsideTheMemoryTtl() {
        AgentConversationMemory snapshot = firstStore.getOrCreateMemory(USER_ID, SESSION_ID);
        assertThat(redis.persist(generationKey())).isTrue();
        assertThat(redis.getExpire(generationKey(), TimeUnit.MILLISECONDS)).isEqualTo(-1L);

        assertThat(snapshot.addExchange(
                AgentTurn.user("refresh ttl"),
                AgentTurn.assistant("refreshed"))).isTrue();

        long generationTtl = redis.getExpire(generationKey(), TimeUnit.MILLISECONDS);
        long memoryTtl = redis.getExpire(memoryKey(), TimeUnit.MILLISECONDS);
        assertThat(generationTtl).isPositive();
        assertThat(memoryTtl).isPositive();
        assertThat(Math.abs(generationTtl - memoryTtl)).isLessThan(1_000L);
    }

    @Test
    void clearRejectsAnAcceptedTurnWithoutDroppingItsCoordinatorLease() {
        AgentTurnCoordinator coordinator = new AgentTurnCoordinator(redis);
        AgentTurnCoordinator.AcquireResult acquired = coordinator.acquire(
                USER_ID,
                SESSION_ID,
                "request-generation-it",
                "turn-generation-it",
                Duration.ofSeconds(30));
        assertThat(acquired.status()).isEqualTo(AgentTurnCoordinator.AcquireStatus.ACQUIRED);
        AgentTurnControl control = AgentTurnControl.create(
                "request-generation-it",
                "turn-generation-it",
                SESSION_ID,
                "connection-generation-it",
                Duration.ofSeconds(30));
        AgentConversationMemory oldSnapshot = firstStore.getOrCreateMemory(USER_ID, SESSION_ID);

        secondStore.clearMemory(USER_ID, SESSION_ID);
        AgentMemoryStore.MemoryWriteResult result = oldSnapshot.addExchange(
                AgentTurn.user("stale fenced question"),
                AgentTurn.assistant("stale fenced answer"),
                control);

        assertThat(result.failureCode()).isEqualTo("SESSION_CLEARED");
        assertThat(redis.hasKey(memoryKey())).isFalse();
        assertThat(coordinator.release(USER_ID, SESSION_ID, "turn-generation-it")).isTrue();
    }

    private static String memoryKey() {
        return "agent:memory:" + USER_ID + ":" + SESSION_ID;
    }

    private static String generationKey() {
        return "agent:memory:generation:" + USER_ID + ":" + SESSION_ID;
    }
}
