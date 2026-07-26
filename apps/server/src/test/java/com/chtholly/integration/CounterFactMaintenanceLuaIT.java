package com.chtholly.integration;

import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.counter.mapper.CounterReactionKey;
import com.chtholly.counter.mapper.CounterPersistenceMapper;
import com.chtholly.counter.mapper.CounterReactionMapper;
import com.chtholly.counter.event.CounterAggregationProcessor;
import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.event.CounterRebuildConsumer;
import com.chtholly.counter.event.CounterTopics;
import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.service.impl.CounterReactionProjectionRebuilder;
import com.chtholly.counter.service.impl.CounterReactionProjectionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
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
        assertThat(redis.hasKey(
                CounterKeys.reactionProjectionCompleteKey("post", entityId))).isFalse();
        assertThat(redis.opsForValue().get(
                CounterKeys.factMaintenanceFenceKey("post", entityId)))
                .isEqualTo("@prepared:owner");
        rebuilder.publishComplete("post", entityId, "owner");
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
        rebuilder.publishComplete("post", entityId, "owner");

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
    void beginWithoutFinalizationCannotPublishCompleteness() {
        String entityId = "7011";
        rebuilder.begin("post", entityId, "owner");

        assertThatThrownBy(() -> rebuilder.publishComplete("post", entityId, "owner"))
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .hasMessageContaining("ownership lost");
        assertThat(redis.hasKey(
                CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                .isFalse();

        rebuilder.abort("post", entityId, "owner");
    }

    @Test
    void missingIndexedPhysicalShardMakesMembershipUnknown() {
        stubFacts(List.of(42L), List.of());
        String entityId = "7003";
        rebuilder.begin("post", entityId, "owner");
        rebuilder.rebuild("post", entityId, "owner", 1L);
        rebuilder.publishComplete("post", entityId, "owner");
        redis.delete(CounterKeys.bitmapKey(
                "like", "post", entityId, BitmapShard.chunkOf(42L)));

        Optional<Boolean> state = projectionStore.read(
                new CounterReactionKey("post", entityId, "like", 42L));

        assertThat(state).isEmpty();
    }

    @Test
    void wrongTypeCompleteMarkerMakesMembershipUnknown() {
        stubFacts(List.of(42L), List.of());
        String entityId = "7009";
        rebuilder.begin("post", entityId, "owner");
        rebuilder.rebuild("post", entityId, "owner", 1L);
        rebuilder.publishComplete("post", entityId, "owner");
        String completeKey =
                CounterKeys.reactionProjectionCompleteKey("post", entityId);
        redis.delete(completeKey);
        redis.opsForHash().put(completeKey, "wrong", "type");

        Optional<Boolean> state = projectionStore.read(
                new CounterReactionKey("post", entityId, "like", 42L));

        assertThat(state).isEmpty();
    }

    @Test
    void partialShardLossCannotBeRecompletedByOneUnrelatedProjectionEvent() {
        long firstUser = 42L;
        long lostUser = BitmapShard.CHUNK_SIZE + 42L;
        stubFacts(List.of(firstUser, lostUser), List.of());
        String entityId = "7007";
        rebuilder.begin("post", entityId, "owner");
        rebuilder.rebuild("post", entityId, "owner", 1L);
        rebuilder.publishComplete("post", entityId, "owner");
        String indexKey = CounterKeys.bitmapShardIndexKey("like", "post", entityId);
        String lostBitmap = CounterKeys.bitmapKey(
                "like", "post", entityId, BitmapShard.chunkOf(lostUser));
        redis.delete(lostBitmap);
        redis.opsForSet().remove(indexKey, lostBitmap);

        projectionStore.project(Map.of(
                new CounterReactionKey("post", entityId, "like", firstUser),
                true));

        assertThat(redis.hasKey(
                CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                .isFalse();
        assertThat(projectionStore.read(
                new CounterReactionKey("post", entityId, "like", lostUser)))
                .isEmpty();
    }

    @Test
    void projectionFailureInvalidatesAPreviouslyCompleteMarker() {
        String entityId = "7017";
        String complete =
                CounterKeys.reactionProjectionCompleteKey("post", entityId);
        String fence =
                CounterKeys.factMaintenanceFenceKey("post", entityId);
        redis.opsForValue().set(
                complete, CounterReactionProjectionStore.COMPLETE_VERSION);
        redis.opsForHash().put(fence, "wrong", "type");

        assertThatThrownBy(() -> projectionStore.project(Map.of(
                new CounterReactionKey("post", entityId, "like", 42L),
                true)))
                .isInstanceOf(
                        CounterReactionProjectionStore.ProjectionBatchException.class);

        assertThat(redis.hasKey(complete)).isFalse();
    }

    @Test
    void projectionKeyFailureAlsoInvalidatesAPreviouslyCompleteMarker() {
        String entityId = "7022";
        String complete =
                CounterKeys.reactionProjectionCompleteKey("post", entityId);
        String bitmap = CounterKeys.bitmapKey(
                "like", "post", entityId, BitmapShard.chunkOf(42L));
        redis.opsForValue().set(
                complete, CounterReactionProjectionStore.COMPLETE_VERSION);
        redis.opsForHash().put(bitmap, "wrong", "type");

        assertThatThrownBy(() -> projectionStore.project(Map.of(
                new CounterReactionKey("post", entityId, "like", 42L),
                true)))
                .isInstanceOf(
                        CounterReactionProjectionStore.ProjectionBatchException.class);

        assertThat(redis.hasKey(complete)).isFalse();
    }

    @Test
    void multiKeyPipelineReportsExactFailuresAndHealthyRetryIsIdempotent() {
        CounterReactionKey first =
                new CounterReactionKey("post", "7101", "like", 11L);
        CounterReactionKey wrongBitmap =
                new CounterReactionKey("post", "7102", "like", 12L);
        CounterReactionKey third =
                new CounterReactionKey("post", "7103", "like", 13L);
        CounterReactionKey wrongFence =
                new CounterReactionKey("post", "7104", "like", 14L);
        CounterReactionKey fifth =
                new CounterReactionKey("post", "7105", "like", 15L);
        List<CounterReactionKey> all =
                List.of(first, wrongBitmap, third, wrongFence, fifth);
        all.forEach(this::prepareCompleteEmptyProjection);
        redis.opsForHash().put(
                CounterKeys.bitmapKey(
                        wrongBitmap.metric(),
                        wrongBitmap.entityType(),
                        wrongBitmap.entityId(),
                        BitmapShard.chunkOf(wrongBitmap.userId())),
                "wrong",
                "type");
        redis.opsForHash().put(
                CounterKeys.factMaintenanceFenceKey(
                        wrongFence.entityType(), wrongFence.entityId()),
                "wrong",
                "type");
        Map<CounterReactionKey, Boolean> targets = new LinkedHashMap<>();
        all.forEach(key -> targets.put(key, true));

        CounterReactionProjectionStore.ProjectionBatchException failure =
                catchThrowableOfType(
                        () -> projectionStore.project(targets),
                        CounterReactionProjectionStore.ProjectionBatchException.class);

        assertThat(failure.failedKeys())
                .containsExactly(wrongBitmap, wrongFence);
        assertThat(failure)
                .hasMessageContaining("post:7102:like:12")
                .hasMessageContaining("post:7104:like:14");
        assertHealthyProjection(first);
        assertHealthyProjection(third);
        assertHealthyProjection(fifth);
        assertThat(redis.hasKey(CounterKeys.reactionProjectionCompleteKey(
                wrongBitmap.entityType(), wrongBitmap.entityId()))).isFalse();
        assertThat(redis.hasKey(CounterKeys.reactionProjectionCompleteKey(
                wrongFence.entityType(), wrongFence.entityId()))).isFalse();

        Map<CounterReactionKey, Boolean> healthyRetry = new LinkedHashMap<>();
        healthyRetry.put(first, true);
        healthyRetry.put(third, true);
        healthyRetry.put(fifth, true);
        projectionStore.project(healthyRetry);

        assertHealthyProjection(first);
        assertHealthyProjection(third);
        assertHealthyProjection(fifth);
    }

    @Test
    void rebuildBeginRepairsAWrongTypeFenceWhileKeepingProjectionIncomplete() {
        String entityId = "7023";
        String complete =
                CounterKeys.reactionProjectionCompleteKey("post", entityId);
        String fence =
                CounterKeys.factMaintenanceFenceKey("post", entityId);
        redis.opsForValue().set(
                complete, CounterReactionProjectionStore.COMPLETE_VERSION);
        redis.opsForHash().put(fence, "wrong", "type");

        rebuilder.begin("post", entityId, "owner");

        assertThat(redis.opsForValue().get(fence)).isEqualTo("owner");
        assertThat(redis.hasKey(complete)).isFalse();
        rebuilder.abort("post", entityId, "owner");
    }

    @Test
    void projectionOverflowInvalidatesCompletenessWithoutWritingTheBit() {
        String entityId = "7018";
        String complete =
                CounterKeys.reactionProjectionCompleteKey("post", entityId);
        String index =
                CounterKeys.bitmapShardIndexKey("like", "post", entityId);
        String indexCount =
                CounterKeys.bitmapShardIndexCountKey("like", "post", entityId);
        CounterReactionKey key =
                new CounterReactionKey("post", entityId, "like", 42L);
        setSds(entityId, 0L, 0xffff_ffffL, 0L);
        redis.opsForSet().add(
                index, CounterReactionProjectionStore.SHARD_INDEX_SENTINEL);
        redis.opsForValue().set(indexCount, "0");
        redis.opsForValue().set(
                complete, CounterReactionProjectionStore.COMPLETE_VERSION);

        assertThatThrownBy(() -> projectionStore.project(Map.of(key, true)))
                .isInstanceOf(
                        CounterReactionProjectionStore.ProjectionBatchException.class);

        assertThat(redis.hasKey(complete)).isFalse();
        assertThat(readSds(entityId))
                .containsExactly(0L, 0xffff_ffffL, 0L, 0L, 0L);
        assertThat(redis.hasKey(CounterKeys.bitmapKey(
                "like", "post", entityId, BitmapShard.chunkOf(42L))))
                .isFalse();
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
    void abortAfterPreparationKeepsACommitFailureInvisible() {
        stubFacts(List.of(42L), List.of());
        String entityId = "7008";
        rebuilder.begin("post", entityId, "owner");
        rebuilder.rebuild("post", entityId, "owner", 2L);

        assertThat(redis.hasKey(
                CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                .isFalse();
        assertThat(redis.opsForValue().get(
                CounterKeys.factMaintenanceFenceKey("post", entityId)))
                .isEqualTo("@prepared:owner");

        rebuilder.abort("post", entityId, "owner");

        assertThat(redis.hasKey(
                CounterKeys.reactionProjectionCompleteKey("post", entityId)))
                .isFalse();
        assertThat(redis.hasKey(
                CounterKeys.factMaintenanceFenceKey("post", entityId)))
                .isFalse();
    }

    @Test
    void eventDuringPreparedRebuildDirtiesFenceAndPreventsStalePublication() {
        stubFacts(List.of(), List.of());
        String entityId = "7010";
        String fence = CounterKeys.factMaintenanceFenceKey("post", entityId);
        String complete =
                CounterKeys.reactionProjectionCompleteKey("post", entityId);
        rebuilder.begin("post", entityId, "owner");
        rebuilder.rebuild("post", entityId, "owner", 2L);

        assertThatThrownBy(() -> projectionStore.project(Map.of(
                new CounterReactionKey("post", entityId, "like", 42L),
                true)))
                .isInstanceOf(
                        CounterReactionProjectionStore.ProjectionBatchException.class)
                .hasMessageContaining("post:" + entityId + ":like:42");

        assertThat(redis.opsForValue().get(fence)).isEqualTo("@dirty:owner");
        assertThat(redis.hasKey(complete)).isFalse();
        assertThatThrownBy(() -> rebuilder.publishComplete("post", entityId, "owner"))
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .hasMessageContaining("ownership lost");

        rebuilder.abort("post", entityId, "owner");

        assertThat(redis.hasKey(fence)).isFalse();
        assertThat(redis.hasKey(complete)).isFalse();
    }

    @Test
    void preparedFenceWinsOverAnInvalidProjectionKeyAndForcesRetry() {
        stubFacts(List.of(), List.of());
        String entityId = "7011";
        String fence = CounterKeys.factMaintenanceFenceKey("post", entityId);
        String complete = CounterKeys.reactionProjectionCompleteKey("post", entityId);
        String counter = CounterKeys.sdsKey("post", entityId);
        rebuilder.begin("post", entityId, "owner");
        rebuilder.rebuild("post", entityId, "owner", 2L);
        redis.delete(counter);
        redis.opsForHash().put(counter, "wrong", "type");

        assertThatThrownBy(() -> projectionStore.project(Map.of(
                new CounterReactionKey("post", entityId, "like", 42L),
                true)))
                .isInstanceOf(
                        CounterReactionProjectionStore.ProjectionBatchException.class)
                .hasMessageContaining("post:" + entityId + ":like:42");

        assertThat(redis.opsForValue().get(fence)).isEqualTo("@dirty:owner");
        assertThat(redis.hasKey(complete)).isFalse();
        assertThatThrownBy(() -> rebuilder.publishComplete("post", entityId, "owner"))
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .hasMessageContaining("ownership lost");
    }

    @Test
    void viewFlushInvalidatesCompletenessWhenItMustInitializeTheSharedSds() {
        String entityId = "7012";
        String aggKey = CounterKeys.aggKey("post", entityId);
        String complete = CounterKeys.reactionProjectionCompleteKey("post", entityId);
        redis.opsForValue().set(complete, CounterReactionProjectionStore.COMPLETE_VERSION);
        redis.opsForHash().put(aggKey, "0", "4");
        redis.opsForSet().add(CounterKeys.aggIndexKey(), aggKey);

        CounterAggregationProcessor processor = new CounterAggregationProcessor(
                redis, mock(CounterPersistenceMapper.class));
        processor.flush();

        assertThat(readSds(entityId)).containsExactly(4L, 0L, 0L, 0L, 0L);
        assertThat(redis.hasKey(complete)).isFalse();
    }

    @Test
    void replayRebuildInvalidatesCompletenessWhenItMustInitializeTheSharedSds()
            throws Exception {
        String entityId = "7013";
        String complete = CounterKeys.reactionProjectionCompleteKey("post", entityId);
        redis.opsForValue().set(complete, CounterReactionProjectionStore.COMPLETE_VERSION);
        ObjectMapper objectMapper = new ObjectMapper();
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        CounterRebuildConsumer consumer = new CounterRebuildConsumer(
                objectMapper,
                redis,
                mock(KafkaTemplate.class),
                mock(DeadLetterMessageService.class));
        CounterEvent event = CounterEvent.of(
                "post", entityId, "view", CounterSchema.IDX_VIEW, 0L, 6, "view-rebuild-7013");

        consumer.onMessage(
                new ConsumerRecord<>(
                        CounterTopics.EVENTS,
                        0,
                        0L,
                        entityId,
                        objectMapper.writeValueAsString(event)),
                acknowledgment);

        verify(acknowledgment).acknowledge();
        assertThat(readSds(entityId)).containsExactly(6L, 0L, 0L, 0L, 0L);
        assertThat(redis.hasKey(complete)).isFalse();
    }

    @Test
    void viewFlushDirtiesPreparedRebuildWhenItMustReplaceTheSharedSds() {
        stubFacts(List.of(42L), List.of());
        String entityId = "7014";
        String fence = CounterKeys.factMaintenanceFenceKey("post", entityId);
        String complete = CounterKeys.reactionProjectionCompleteKey("post", entityId);
        String aggKey = CounterKeys.aggKey("post", entityId);
        rebuilder.begin("post", entityId, "owner");
        rebuilder.rebuild("post", entityId, "owner", 2L);
        redis.delete(CounterKeys.sdsKey("post", entityId));
        redis.opsForHash().put(aggKey, "0", "4");
        redis.opsForSet().add(CounterKeys.aggIndexKey(), aggKey);

        new CounterAggregationProcessor(redis, mock(CounterPersistenceMapper.class))
                .flush();

        assertThat(readSds(entityId)).containsExactly(4L, 0L, 0L, 0L, 0L);
        assertThat(redis.opsForValue().get(fence)).isEqualTo("@dirty:owner");
        assertThat(redis.hasKey(complete)).isFalse();
        assertThatThrownBy(() -> rebuilder.publishComplete("post", entityId, "owner"))
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .hasMessageContaining("ownership lost");
    }

    @Test
    void replayRebuildDirtiesPreparedRebuildWhenItMustReplaceTheSharedSds()
            throws Exception {
        stubFacts(List.of(42L), List.of());
        String entityId = "7015";
        String fence = CounterKeys.factMaintenanceFenceKey("post", entityId);
        String complete = CounterKeys.reactionProjectionCompleteKey("post", entityId);
        rebuilder.begin("post", entityId, "owner");
        rebuilder.rebuild("post", entityId, "owner", 2L);
        redis.delete(CounterKeys.sdsKey("post", entityId));
        ObjectMapper objectMapper = new ObjectMapper();
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        CounterRebuildConsumer consumer = new CounterRebuildConsumer(
                objectMapper,
                redis,
                mock(KafkaTemplate.class),
                mock(DeadLetterMessageService.class));
        CounterEvent event = CounterEvent.of(
                "post", entityId, "view", CounterSchema.IDX_VIEW, 0L, 6, "view-rebuild-7015");

        consumer.onMessage(
                new ConsumerRecord<>(
                        CounterTopics.EVENTS,
                        0,
                        0L,
                        entityId,
                        objectMapper.writeValueAsString(event)),
                acknowledgment);

        verify(acknowledgment).acknowledge();
        assertThat(readSds(entityId)).containsExactly(6L, 0L, 0L, 0L, 0L);
        assertThat(redis.opsForValue().get(fence)).isEqualTo("@dirty:owner");
        assertThat(redis.hasKey(complete)).isFalse();
        assertThatThrownBy(() -> rebuilder.publishComplete("post", entityId, "owner"))
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .hasMessageContaining("ownership lost");
    }

    @Test
    void replayDedupeIsNotConsumedWhenRedisValidationFailsBeforeTheAtomicWrite() {
        String entityId = "7016";
        String eventId = "view-rebuild-7016";
        String fence = CounterKeys.factMaintenanceFenceKey("post", entityId);
        String dedupe = CounterKeys.eventDedupeKey(eventId);
        redis.opsForHash().put(fence, "invalid", "type");
        CounterRebuildConsumer consumer = new CounterRebuildConsumer(
                new ObjectMapper(),
                redis,
                mock(KafkaTemplate.class),
                mock(DeadLetterMessageService.class));
        CounterEvent event = CounterEvent.of(
                "post", entityId, "view", CounterSchema.IDX_VIEW, 0L, 6, eventId);

        assertThatThrownBy(() ->
                ReflectionTestUtils.invokeMethod(consumer, "applyRebuildEvent", event))
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .hasMessageContaining("maintenance fence has an invalid Redis type");
        assertThat(redis.hasKey(dedupe)).isFalse();

        redis.delete(fence);
        Boolean applied =
                ReflectionTestUtils.invokeMethod(consumer, "applyRebuildEvent", event);

        assertThat(applied).isTrue();
        assertThat(readSds(entityId)).containsExactly(6L, 0L, 0L, 0L, 0L);
        assertThat(redis.opsForValue().get(dedupe)).isEqualTo("1");
    }

    @Test
    void viewAggregationDedupeIsNotConsumedWhenHashIncrementFails() {
        String entityId = "7019";
        String eventId = "view-aggregation-7019";
        String aggKey = CounterKeys.aggKey("post", entityId);
        String dedupe = "counter:event:" + eventId;
        redis.opsForValue().set(aggKey, "wrong-type");
        CounterPersistenceMapper persistenceMapper =
                mock(CounterPersistenceMapper.class);
        CounterEvent event = CounterEvent.of(
                eventId,
                "post",
                entityId,
                "view",
                CounterSchema.IDX_VIEW,
                0L,
                1);
        when(persistenceMapper.insertInbox(event)).thenReturn(1, 1);
        CounterAggregationProcessor processor =
                new CounterAggregationProcessor(redis, persistenceMapper);

        assertThatThrownBy(() -> processor.applyBatch(List.of(event)))
                .isInstanceOf(RuntimeException.class);
        assertThat(redis.hasKey(dedupe)).isFalse();

        redis.delete(aggKey);
        assertThat(processor.applyBatch(List.of(event))).isEqualTo(1);
        assertThat(redis.opsForHash().get(aggKey, "0")).isEqualTo("1");
        assertThat(redis.opsForValue().get(dedupe)).isEqualTo("1");
    }

    @Test
    void viewAggregationOverflowDoesNotConsumeDedupe() {
        String entityId = "7024";
        String eventId = "view-aggregation-7024";
        String aggKey = CounterKeys.aggKey("post", entityId);
        String dedupe = "counter:event:" + eventId;
        redis.opsForHash().put(aggKey, "0", Long.toString(Long.MAX_VALUE));
        CounterPersistenceMapper persistenceMapper =
                mock(CounterPersistenceMapper.class);
        CounterEvent event = CounterEvent.of(
                eventId,
                "post",
                entityId,
                "view",
                CounterSchema.IDX_VIEW,
                0L,
                1);
        when(persistenceMapper.insertInbox(event)).thenReturn(1);
        CounterAggregationProcessor processor =
                new CounterAggregationProcessor(redis, persistenceMapper);

        assertThatThrownBy(() -> processor.applyBatch(List.of(event)))
                .isInstanceOf(RuntimeException.class);

        assertThat(redis.opsForHash().get(aggKey, "0"))
                .isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(redis.hasKey(dedupe)).isFalse();
    }

    @Test
    void viewFlushRetainsPendingDeltaInsteadOfWrappingUnsignedInt32() {
        String entityId = "7020";
        String aggKey = CounterKeys.aggKey("post", entityId);
        setSds(entityId, 0xffff_ffffL, 0L, 0L);
        redis.opsForHash().put(aggKey, "0", "1");
        redis.opsForSet().add(CounterKeys.aggIndexKey(), aggKey);

        new CounterAggregationProcessor(redis, mock(CounterPersistenceMapper.class))
                .flush();

        assertThat(readSds(entityId))
                .containsExactly(0xffff_ffffL, 0L, 0L, 0L, 0L);
        assertThat(redis.opsForHash().get(aggKey, "0")).isEqualTo("1");
        assertThat(redis.opsForSet().isMember(
                CounterKeys.aggIndexKey(), aggKey)).isTrue();
    }

    @Test
    void replayOverflowDoesNotConsumeDedupeOrWrapUnsignedInt32() {
        String entityId = "7021";
        String eventId = "view-rebuild-7021";
        String dedupe = CounterKeys.eventDedupeKey(eventId);
        setSds(entityId, 0xffff_ffffL, 0L, 0L);
        CounterRebuildConsumer consumer = new CounterRebuildConsumer(
                new ObjectMapper(),
                redis,
                mock(KafkaTemplate.class),
                mock(DeadLetterMessageService.class));
        CounterEvent event = CounterEvent.of(
                "post",
                entityId,
                "view",
                CounterSchema.IDX_VIEW,
                0L,
                1,
                eventId);

        assertThatThrownBy(() ->
                ReflectionTestUtils.invokeMethod(consumer, "applyRebuildEvent", event))
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .hasMessageContaining("overflow unsigned Int32");

        assertThat(readSds(entityId))
                .containsExactly(0xffff_ffffL, 0L, 0L, 0L, 0L);
        assertThat(redis.hasKey(dedupe)).isFalse();
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
    void ownershipLossAfterDirectWriteCannotOverwriteANewCompleteProjection() {
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
                        redis.delete(CounterKeys.bitmapKey("like", "post", entityId, 0L));
                        redis.delete(CounterKeys.bitmapShardIndexKey(
                                "like", "post", entityId));
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

    private void prepareCompleteEmptyProjection(CounterReactionKey key) {
        setSds(key.entityId(), 0L, 0L, 0L);
        redis.opsForSet().add(
                CounterKeys.bitmapShardIndexKey(
                        key.metric(), key.entityType(), key.entityId()),
                CounterReactionProjectionStore.SHARD_INDEX_SENTINEL);
        redis.opsForValue().set(
                CounterKeys.bitmapShardIndexCountKey(
                        key.metric(), key.entityType(), key.entityId()),
                "0");
        redis.opsForValue().set(
                CounterKeys.reactionProjectionCompleteKey(
                        key.entityType(), key.entityId()),
                CounterReactionProjectionStore.COMPLETE_VERSION);
    }

    private void assertHealthyProjection(CounterReactionKey key) {
        String bitmap = CounterKeys.bitmapKey(
                key.metric(), key.entityType(), key.entityId(),
                BitmapShard.chunkOf(key.userId()));
        assertThat(redis.opsForValue().getBit(
                bitmap, BitmapShard.bitOf(key.userId()))).isTrue();
        assertThat(readSds(key.entityId()))
                .containsExactly(0L, 1L, 0L, 0L, 0L);
        assertThat(redis.opsForSet().members(
                CounterKeys.bitmapShardIndexKey(
                        key.metric(), key.entityType(), key.entityId())))
                .containsExactlyInAnyOrder(
                        CounterReactionProjectionStore.SHARD_INDEX_SENTINEL,
                        bitmap);
        assertThat(redis.opsForValue().get(
                CounterKeys.bitmapShardIndexCountKey(
                        key.metric(), key.entityType(), key.entityId())))
                .isEqualTo("1");
        assertThat(redis.opsForValue().get(
                CounterKeys.reactionProjectionCompleteKey(
                        key.entityType(), key.entityId())))
                .isEqualTo(CounterReactionProjectionStore.COMPLETE_VERSION);
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
