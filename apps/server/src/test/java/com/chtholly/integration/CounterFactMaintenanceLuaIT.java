package com.chtholly.integration;

import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.mapper.CounterReactionMapper;
import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.service.impl.CounterReactionProjectionRebuilder;
import com.chtholly.counter.service.impl.CounterReactionProjectionStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** Executes MySQL-driven reaction projection recovery against Redis 5. */
class CounterFactMaintenanceLuaIT {

    private static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>(
            DockerImageName.parse("redis:5-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory lettuce;
    private static StringRedisTemplate redis;

    private CounterReactionMapper reactionMapper;
    private CounterReactionProjectionRebuilder rebuilder;
    private CounterReactionProjectionStore projectionStore;

    @BeforeAll
    static void startRedis() {
        REDIS_CONTAINER.start();
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                REDIS_CONTAINER.getHost(), REDIS_CONTAINER.getMappedPort(6379));
        lettuce = new LettuceConnectionFactory(standalone);
        lettuce.afterPropertiesSet();
        redis = new StringRedisTemplate(lettuce);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (lettuce != null) {
            lettuce.destroy();
        }
        REDIS_CONTAINER.stop();
    }

    @BeforeEach
    void setUp() {
        try (RedisConnection connection = lettuce.getConnection()) {
            connection.serverCommands().flushAll();
        }
        reactionMapper = mock(CounterReactionMapper.class);
        rebuilder = new CounterReactionProjectionRebuilder(redis, reactionMapper);
        projectionStore = new CounterReactionProjectionStore(redis);
    }

    @Test
    void rebuildsMysqlFactsAcrossShardsAndDeletesUnindexedStaleBits() {
        stubFacts(List.of(1L, 32_769L), List.of(32_770L));
        String entityId = "7001";
        String staleLike = CounterKeys.bitmapKey("like", "post", entityId, 3L);
        redis.opsForValue().setBit(staleLike, 99L, true);
        redis.opsForSet().add(
                CounterKeys.bitmapShardIndexKey("like", "post", entityId), "@v1");
        redis.opsForValue().set(
                CounterKeys.bitmapShardIndexCountKey("like", "post", entityId), "0");
        setSds(entityId, 11L, 99L, 88L);
        redis.opsForValue().set(
                CounterKeys.reactionProjectionCompleteKey("post", entityId),
                CounterReactionProjectionStore.COMPLETE_VERSION);

        rebuilder.begin("post", entityId, "owner");
        assertThat(redis.hasKey(CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                .isFalse();
        CounterReactionProjectionRebuilder.RebuildResult result =
                rebuilder.rebuild("post", entityId, "owner", 7L);

        assertThat(result)
                .isEqualTo(new CounterReactionProjectionRebuilder.RebuildResult(2L, 1L, 7L));
        assertThat(redis.hasKey(staleLike)).isFalse();
        assertThat(redis.opsForSet().members(
                CounterKeys.bitmapShardIndexKey("like", "post", entityId)))
                .containsExactlyInAnyOrder(
                        CounterReactionProjectionStore.SHARD_INDEX_SENTINEL,
                        CounterKeys.bitmapKey("like", "post", entityId, 0L),
                        CounterKeys.bitmapKey("like", "post", entityId, 1L));
        assertThat(redis.opsForValue().get(
                CounterKeys.bitmapShardIndexCountKey("like", "post", entityId)))
                .isEqualTo("2");
        assertThat(redis.opsForValue().get(
                CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                .isEqualTo(CounterReactionProjectionStore.COMPLETE_VERSION);
        assertThat(redis.hasKey(CounterKeys.factMaintenanceFenceKey("post", entityId)))
                .isFalse();
        assertThat(redis.opsForValue().get(CounterKeys.factEpochKey("post", entityId)))
                .isEqualTo("7");
        assertThat(readSds(entityId)).containsExactly(11L, 2L, 1L, 0L, 0L);

        assertThat(projectionStore.read(
                new CounterReactionKey("post", entityId, "like", 1L))).contains(true);
        assertThat(projectionStore.read(
                new CounterReactionKey("post", entityId, "like", 2L))).contains(false);
        assertThat(projectionStore.read(
                new CounterReactionKey("post", entityId, "fav", 32_770L))).contains(true);
    }

    @Test
    void emptyMysqlFactsPublishAnExplicitCompleteEmptyProjection() {
        stubFacts(List.of(), List.of());
        String entityId = "7002";
        String stale = CounterKeys.bitmapKey("fav", "post", entityId, 0L);
        redis.opsForValue().setBit(stale, 42L, true);

        rebuilder.begin("post", entityId, "owner");
        CounterReactionProjectionRebuilder.RebuildResult result =
                rebuilder.rebuild("post", entityId, "owner", 1L);

        assertThat(result)
                .isEqualTo(new CounterReactionProjectionRebuilder.RebuildResult(0L, 0L, 1L));
        assertThat(redis.hasKey(stale)).isFalse();
        assertThat(redis.opsForSet().members(
                CounterKeys.bitmapShardIndexKey("like", "post", entityId)))
                .containsExactly(CounterReactionProjectionStore.SHARD_INDEX_SENTINEL);
        assertThat(redis.opsForSet().members(
                CounterKeys.bitmapShardIndexKey("fav", "post", entityId)))
                .containsExactly(CounterReactionProjectionStore.SHARD_INDEX_SENTINEL);
        assertThat(redis.opsForValue().get(
                CounterKeys.bitmapShardIndexCountKey("like", "post", entityId)))
                .isEqualTo("0");
        assertThat(redis.opsForValue().get(
                CounterKeys.bitmapShardIndexCountKey("fav", "post", entityId)))
                .isEqualTo("0");
        assertThat(projectionStore.read(
                new CounterReactionKey("post", entityId, "like", 42L))).contains(false);
    }

    @Test
    void missingIndexedPhysicalShardMakesMembershipUnknown() {
        stubFacts(List.of(42L), List.of());
        String entityId = "7003";
        rebuilder.begin("post", entityId, "owner");
        rebuilder.rebuild("post", entityId, "owner", 1L);
        redis.delete(CounterKeys.bitmapKey(
                "like", "post", entityId, BitmapShard.chunkOf(42L)));

        Optional<Boolean> state = projectionStore.read(
                new CounterReactionKey("post", entityId, "like", 42L));

        assertThat(state).isEmpty();
    }

    @Test
    void finalizationFailureNeverPublishesCompleteness() {
        stubFacts(List.of(42L), List.of());
        String entityId = "7004";
        redis.opsForHash().put(CounterKeys.sdsKey("post", entityId), "wrong", "type");
        rebuilder.begin("post", entityId, "owner");

        assertThatThrownBy(() -> rebuilder.rebuild("post", entityId, "owner", 2L))
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .hasMessageContaining("invalid Redis type");

        assertThat(redis.hasKey(CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                .isFalse();
        rebuilder.abort("post", entityId, "owner");
        assertThat(redis.hasKey(CounterKeys.factMaintenanceFenceKey("post", entityId)))
                .isFalse();
    }

    @Test
    void lostFenceCannotPublishCompletenessOrDeleteTheNewOwner() {
        stubFacts(List.of(42L), List.of());
        String entityId = "7005";
        String fence = CounterKeys.factMaintenanceFenceKey("post", entityId);
        rebuilder.begin("post", entityId, "old-owner");
        redis.opsForValue().set(fence, "new-owner");

        assertThatThrownBy(() -> rebuilder.rebuild("post", entityId, "old-owner", 2L))
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .hasMessageContaining("ownership lost");
        rebuilder.abort("post", entityId, "old-owner");

        assertThat(redis.opsForValue().get(fence)).isEqualTo("new-owner");
        assertThat(redis.hasKey(CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                .isFalse();
    }

    @Test
    void ownershipLossAfterStagingCannotOverwriteANewCompleteProjection() {
        String entityId = "7006";
        String fence = CounterKeys.factMaintenanceFenceKey("post", entityId);
        long newOwnerUserId = BitmapShard.CHUNK_SIZE * 3L + 9L;
        String newOwnerBitmap = CounterKeys.bitmapKey(
                "like", "post", entityId, BitmapShard.chunkOf(newOwnerUserId));
        AtomicBoolean takeoverInstalled = new AtomicBoolean();
        when(reactionMapper.listUserIdsAfter(
                anyString(), anyString(), anyString(), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    String metric = invocation.getArgument(2);
                    if ("like".equals(metric)) {
                        return List.of(42L);
                    }
                    if (takeoverInstalled.compareAndSet(false, true)) {
                        redis.opsForValue().set(fence, "new-owner");
                        redis.opsForValue().setBit(
                                newOwnerBitmap, BitmapShard.bitOf(newOwnerUserId), true);
                        redis.opsForSet().add(
                                CounterKeys.bitmapShardIndexKey(
                                        "like", "post", entityId),
                                CounterReactionProjectionStore.SHARD_INDEX_SENTINEL,
                                newOwnerBitmap);
                        redis.opsForValue().set(
                                CounterKeys.bitmapShardIndexCountKey(
                                        "like", "post", entityId),
                                "1");
                        redis.opsForValue().set(
                                CounterKeys.reactionProjectionCompleteKey("post", entityId),
                                CounterReactionProjectionStore.COMPLETE_VERSION);
                    }
                    return List.of();
                });
        rebuilder.begin("post", entityId, "old-owner");

        assertThatThrownBy(() -> rebuilder.rebuild("post", entityId, "old-owner", 2L))
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .hasMessageContaining("ownership lost");

        assertThat(redis.opsForValue().get(fence)).isEqualTo("new-owner");
        assertThat(redis.opsForValue().getBit(
                newOwnerBitmap, BitmapShard.bitOf(newOwnerUserId))).isTrue();
        assertThat(redis.opsForSet().members(
                CounterKeys.bitmapShardIndexKey("like", "post", entityId)))
                .containsExactlyInAnyOrder(
                        CounterReactionProjectionStore.SHARD_INDEX_SENTINEL,
                        newOwnerBitmap);
        assertThat(redis.opsForValue().get(
                CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                .isEqualTo(CounterReactionProjectionStore.COMPLETE_VERSION);
    }

    private void stubFacts(List<Long> likes, List<Long> favorites) {
        reset(reactionMapper);
        when(reactionMapper.listUserIdsAfter(
                anyString(), anyString(), anyString(), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    String metric = invocation.getArgument(2);
                    long after = invocation.getArgument(3);
                    int limit = invocation.getArgument(4);
                    List<Long> source = "like".equals(metric) ? likes : favorites;
                    return source.stream()
                            .filter(userId -> userId > after)
                            .limit(limit)
                            .toList();
                });
    }

    private static void setSds(String entityId, long view, long like, long favorite) {
        byte[] raw = ByteBuffer.allocate(CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE)
                .putInt((int) view)
                .putInt((int) like)
                .putInt((int) favorite)
                .putInt(0)
                .putInt(0)
                .array();
        byte[] key = CounterKeys.sdsKey("post", entityId).getBytes(StandardCharsets.UTF_8);
        redis.execute((RedisCallback<Void>) connection -> {
            connection.stringCommands().set(key, raw);
            return null;
        });
    }

    private static List<Long> readSds(String entityId) {
        byte[] key = CounterKeys.sdsKey("post", entityId).getBytes(StandardCharsets.UTF_8);
        byte[] raw = redis.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(key));
        assertThat(raw).isNotNull();
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return List.of(
                Integer.toUnsignedLong(buffer.getInt()),
                Integer.toUnsignedLong(buffer.getInt()),
                Integer.toUnsignedLong(buffer.getInt()),
                Integer.toUnsignedLong(buffer.getInt()),
                Integer.toUnsignedLong(buffer.getInt()));
    }
}
