package com.chtholly.integration;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.counter.event.CounterAggregationProcessor;
import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.mapper.CounterEntityIdentity;
import com.chtholly.counter.mapper.CounterPersistenceMapper;
import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.service.CounterFactMaintenanceService;
import com.chtholly.counter.service.CounterFactMaintenanceService.ManagedPostReactionState;
import com.chtholly.counter.service.CounterFactMaintenanceService.PostReactionReconciliationResult;
import com.chtholly.counter.service.impl.CounterBitmapIndexService;
import com.chtholly.counter.service.impl.CounterCalibrationService;
import com.chtholly.counter.service.impl.CounterFactMaintenanceServiceImpl;
import com.chtholly.counter.service.impl.CounterServiceImpl;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Executes counter fact reconciliation against Redis 5 so the embedded Lua contract is covered.
 */
class CounterFactMaintenanceLuaIT {

    private static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>(
            DockerImageName.parse("redis:5-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory lettuce;
    private static StringRedisTemplate redis;
    private static RedissonClient redisson;

    private UserMapper userMapper;
    private CounterPersistenceMapper counterPersistenceMapper;
    private CounterBitmapIndexService bitmapIndex;
    private CounterFactMaintenanceService service;

    @BeforeAll
    static void startRedis() {
        try {
            REDIS_CONTAINER.start();
            RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                    REDIS_CONTAINER.getHost(), REDIS_CONTAINER.getMappedPort(6379));
            lettuce = new LettuceConnectionFactory(standalone);
            lettuce.afterPropertiesSet();
            redis = new StringRedisTemplate(lettuce);
            redis.afterPropertiesSet();

            Config config = new Config();
            config.useSingleServer().setAddress("redis://"
                    + REDIS_CONTAINER.getHost() + ":" + REDIS_CONTAINER.getMappedPort(6379));
            redisson = Redisson.create(config);
        } catch (RuntimeException | Error exception) {
            closeRedisResources();
            throw exception;
        }
    }

    @AfterAll
    static void stopRedis() {
        closeRedisResources();
    }

    @BeforeEach
    void setUp() {
        try (RedisConnection connection = lettuce.getConnection()) {
            connection.serverCommands().flushAll();
        }
        userMapper = mock(UserMapper.class);
        counterPersistenceMapper = mock(CounterPersistenceMapper.class);
        bitmapIndex = new CounterBitmapIndexService(redis, 500);
        service = new CounterFactMaintenanceServiceImpl(redis, redisson, userMapper, bitmapIndex);
    }

    @Test
    void reconcilesManagedAndOrphanBitsWhilePreservingNaturalFactsAndUnmanagedCounters() {
        ReconciliationScenario scenario = seedScenario(9_901_001L, 101L);

        PostReactionReconciliationResult result = reconcile(scenario);

        assertThat(result).isEqualTo(new PostReactionReconciliationResult(
                scenario.postId(), 2L, 2L, 2L, 2L, 2L));
        assertDesiredBits(scenario);

        long likeBitCount = bitCount("like", scenario.postId());
        long favBitCount = bitCount("fav", scenario.postId());
        assertThat(bitmapKeys("like", scenario.postId())).hasSize(2);
        assertThat(bitmapKeys("fav", scenario.postId())).hasSize(2);
        assertThat(redis.hasKey(bitmapKey("like", scenario.postId(), scenario.managedFavUser())))
                .isFalse();
        assertThat(redis.hasKey(bitmapKey("fav", scenario.postId(), scenario.managedLikeUser())))
                .isFalse();
        assertThat(redis.hasKey(bitmapKey("like", scenario.postId(), scenario.orphanUser())))
                .isFalse();
        assertThat(redis.hasKey(bitmapKey("fav", scenario.postId(), scenario.orphanUser())))
                .isFalse();
        assertThat(likeBitCount).isEqualTo(2L);
        assertThat(favBitCount).isEqualTo(2L);
        assertThat(result.likeTotal()).isEqualTo(likeBitCount);
        assertThat(result.favTotal()).isEqualTo(favBitCount);

        byte[] reconciledSds = rawString(scenario.countKey());
        assertThat(reconciledSds).isNotNull().hasSize(CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE);
        assertThat(field(reconciledSds, CounterSchema.IDX_VIEW))
                .containsExactly(field(scenario.initialSds(), CounterSchema.IDX_VIEW));
        assertThat(field(reconciledSds, CounterSchema.IDX_LIKE))
                .containsExactly(unsignedInt32(2L));
        assertThat(field(reconciledSds, CounterSchema.IDX_FAV))
                .containsExactly(unsignedInt32(2L));
        assertThat(field(reconciledSds, 3)).containsExactly(field(scenario.initialSds(), 3));
        assertThat(field(reconciledSds, 4)).containsExactly(field(scenario.initialSds(), 4));

        assertThat(redis.opsForHash().entries(scenario.aggregateKey()))
                .containsExactlyEntriesOf(Map.of("0", "17"));
        assertThat(redis.opsForSet().members(CounterKeys.aggIndexKey()))
                .containsExactlyInAnyOrder(scenario.aggregateKey(), scenario.unrelatedAggregateKey());
    }

    @Test
    void repeatingTheSameReconciliationDoesNotDriftBitsOrCounters() {
        ReconciliationScenario scenario = seedScenario(9_902_001L, 201L);

        PostReactionReconciliationResult first = reconcile(scenario);
        RedisStateSnapshot afterFirst = snapshot(scenario.trackedKeys());
        PostReactionReconciliationResult second = reconcile(scenario);
        RedisStateSnapshot afterSecond = snapshot(scenario.trackedKeys());

        assertThat(first.managedSetCount()).isEqualTo(2L);
        assertThat(first.managedClearCount()).isEqualTo(2L);
        assertThat(first.orphanClearCount()).isEqualTo(2L);
        assertThat(second.managedSetCount()).isZero();
        assertThat(second.managedClearCount()).isZero();
        assertThat(second.orphanClearCount()).isZero();
        assertThat(second.likeTotal()).isEqualTo(first.likeTotal());
        assertThat(second.favTotal()).isEqualTo(first.favTotal());
        assertThat(afterSecond).isEqualTo(afterFirst);
        assertThat(redis.opsForValue().get(CounterKeys.factEpochKey(
                "post", String.valueOf(scenario.postId())))).isEqualTo("2");
        assertDesiredBits(scenario);
    }

    @ParameterizedTest(name = "{0} wrong type fails before the first bitmap write")
    @EnumSource(CoreWrongType.class)
    void wrongCoreTypeFailsBeforeTheFirstBitmapWrite(CoreWrongType wrongType) {
        long postId = wrongType == CoreWrongType.COUNT ? 9_903_001L : 9_904_001L;
        long userId = (wrongType == CoreWrongType.COUNT ? 301L : 401L)
                * BitmapShard.CHUNK_SIZE + 19L;
        WrongTypeScenario scenario = seedWrongTypeScenario(postId, userId, wrongType);

        assertThat(getBit(scenario.likeKey(), scenario.bitOffset()))
                .as("the desired like requires the Lua script to execute SETBIT")
                .isFalse();
        assertThat(getBit(scenario.favKey(), scenario.bitOffset()))
                .as("the desired favorite removal requires the Lua script to execute SETBIT")
                .isTrue();
        RedisStateSnapshot before = snapshot(scenario.trackedKeys());

        Throwable thrown = catchThrowable(() -> service.reconcileManagedPostReactions(
                Set.of(scenario.userId()),
                Set.of(scenario.postId()),
                Map.of(scenario.postId(), new ManagedPostReactionState(
                        Set.of(scenario.userId()), Set.of()))));

        assertThat(thrown)
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("counter core key has an invalid Redis type");
        assertThat(snapshot(scenario.trackedKeys())).isEqualTo(before);
        assertThat(getBit(scenario.likeKey(), scenario.bitOffset())).isFalse();
        assertThat(getBit(scenario.favKey(), scenario.bitOffset())).isTrue();
    }

    @Test
    void delayedReactionEventAfterMaintenanceCannotDoubleCountExactBitmapFact() {
        long postId = 90_001L;
        long userId = 42L;
        AtomicReference<CounterEvent> delayed = new AtomicReference<>();
        CounterServiceImpl counterService = new CounterServiceImpl(
                redis, delayed::set, mock(PostMapper.class), userMapper,
                new CounterCalibrationService(
                        redis, redisson, counterPersistenceMapper, bitmapIndex, false, 50));
        assertThat(counterService.like("post", String.valueOf(postId), userId)).isTrue();
        assertThat(delayed.get().getFactEpoch()).isEqualTo(1L);

        service.reconcileManagedPostReactions(
                Set.of(userId), Set.of(postId),
                Map.of(postId, new ManagedPostReactionState(Set.of(userId), Set.of())));

        assertThat(bitCount("like", postId)).isEqualTo(1L);
        assertThat(field(rawString(CounterKeys.sdsKey("post", String.valueOf(postId))),
                CounterSchema.IDX_LIKE)).containsExactly(unsignedInt32(1L));
        assertThat(redis.opsForHash().get(
                CounterKeys.aggKey("post", String.valueOf(postId)),
                String.valueOf(CounterSchema.IDX_LIKE))).isNull();
        assertThat(redis.opsForValue().get(CounterKeys.factEpochKey("post", String.valueOf(postId))))
                .isEqualTo("2");
    }

    @Test
    void pendingOldReactionAggregationIsClearedByMaintenanceBeforeFlush() {
        long postId = 90_002L;
        long userId = 43L;
        CounterServiceImpl counterService = new CounterServiceImpl(
                redis, ignored -> {}, mock(PostMapper.class), userMapper,
                new CounterCalibrationService(
                        redis, redisson, counterPersistenceMapper, bitmapIndex, false, 50));

        assertThat(counterService.like("post", String.valueOf(postId), userId)).isTrue();
        redis.opsForHash().put(
                CounterKeys.aggKey("post", String.valueOf(postId)),
                String.valueOf(CounterSchema.IDX_LIKE), "1");
        redis.opsForSet().add(
                CounterKeys.aggIndexKey(), CounterKeys.aggKey("post", String.valueOf(postId)));
        assertThat(redis.opsForHash().get(
                CounterKeys.aggKey("post", String.valueOf(postId)),
                String.valueOf(CounterSchema.IDX_LIKE))).isEqualTo("1");

        service.reconcileManagedPostReactions(
                Set.of(userId), Set.of(postId),
                Map.of(postId, new ManagedPostReactionState(Set.of(userId), Set.of())));
        assertThat(bitCount("like", postId)).isEqualTo(1L);
        assertThat(field(rawString(CounterKeys.sdsKey("post", String.valueOf(postId))),
                CounterSchema.IDX_LIKE)).containsExactly(unsignedInt32(1L));
        assertThat(redis.opsForHash().get(
                CounterKeys.aggKey("post", String.valueOf(postId)),
                String.valueOf(CounterSchema.IDX_LIKE))).isNull();
    }

    @Test
    void activeFenceRejectsNormalToggleAndReleasedFenceCarriesCurrentEpoch() {
        long postId = 90_003L;
        long userId = 44L;
        AtomicReference<CounterEvent> published = new AtomicReference<>();
        CounterServiceImpl counterService = new CounterServiceImpl(
                redis, published::set, mock(PostMapper.class), userMapper,
                new CounterCalibrationService(
                        redis, redisson, counterPersistenceMapper, bitmapIndex, false, 50));
        String fenceKey = CounterKeys.factMaintenanceFenceKey("post", String.valueOf(postId));
        String epochKey = CounterKeys.factEpochKey("post", String.valueOf(postId));
        String lockKey = CounterKeys.factMaintenanceLockKey("post", String.valueOf(postId));
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> holder = executor.submit(() -> {
            org.redisson.api.RLock activeLock = redisson.getLock(lockKey);
            activeLock.lock();
            try {
                redis.opsForValue().set(fenceKey, "maintenance-owner");
                lockHeld.countDown();
                releaseLock.await();
            } finally {
                activeLock.unlock();
            }
            return null;
        });
        try {
            assertThat(lockHeld.await(5L, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> counterService.like("post", String.valueOf(postId), userId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getHttpStatus()).isEqualTo(503));
            assertThat(getBit("like", postId, userId)).isFalse();
            assertThat(published.get()).isNull();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } finally {
            releaseLock.countDown();
            try {
                holder.get(5L, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            } finally {
                executor.shutdownNow();
            }
        }

        redis.delete(fenceKey);
        redis.opsForValue().set(epochKey, "7");
        assertThat(counterService.like("post", String.valueOf(postId), userId)).isTrue();
        assertThat(published.get().getFactEpoch()).isEqualTo(8L);
    }

    @Test
    void calibrationTakesOverAStaleFenceWithoutExpiryUntilMysqlPersistenceCompletes() {
        String entityId = "90006";
        String fenceKey = CounterKeys.factMaintenanceFenceKey("post", entityId);
        redis.opsForValue().set(fenceKey, "stale-owner", 10L, TimeUnit.SECONDS);
        AtomicLong ttlDuringPersistence = new AtomicLong(Long.MIN_VALUE);
        doAnswer(invocation -> {
            Long ttl = redis.getExpire(fenceKey, TimeUnit.MILLISECONDS);
            ttlDuringPersistence.set(ttl == null ? Long.MIN_VALUE : ttl);
            return null;
        }).when(counterPersistenceMapper).replaceReactionSnapshots("post", entityId, 0L, 0L, 1L);
        CounterCalibrationService calibration =
                new CounterCalibrationService(
                        redis, redisson, counterPersistenceMapper, bitmapIndex, false, 50);

        CounterCalibrationService.ReconciliationResult result =
                calibration.reconcileEntity("post", entityId);

        assertThat(result).isEqualTo(new CounterCalibrationService.ReconciliationResult(0L, 0L, 1L));
        assertThat(ttlDuringPersistence.get()).isEqualTo(-1L);
        assertThat(redis.hasKey(fenceKey)).isFalse();
    }

    @Test
    void toggleTakesOverAStaleFenceEvenWithoutAnExistingBitmapOrSnapshotCandidate() {
        String entityId = "90008";
        long userId = 48L;
        String fenceKey = CounterKeys.factMaintenanceFenceKey("post", entityId);
        redis.opsForValue().set(fenceKey, "crashed-owner");
        AtomicReference<CounterEvent> published = new AtomicReference<>();
        CounterServiceImpl counterService = new CounterServiceImpl(
                redis, published::set, mock(PostMapper.class), userMapper,
                new CounterCalibrationService(
                        redis, redisson, counterPersistenceMapper, bitmapIndex, false, 50));

        assertThat(counterService.like("post", entityId, userId)).isTrue();

        assertThat(redis.hasKey(fenceKey)).isFalse();
        assertThat(getBit("like", Long.parseLong(entityId), userId)).isTrue();
        assertThat(field(rawString(CounterKeys.sdsKey("post", entityId)), CounterSchema.IDX_LIKE))
                .containsExactly(unsignedInt32(1L));
        assertThat(published.get()).isNotNull();
    }

    @Test
    void missingDerivedShardIndexNeverOverwritesAStillPresentBitmapAuthority() {
        String entityId = "90009";
        long userId = 49L;
        String bitmapKey = CounterKeys.bitmapKey(
                "like", "post", entityId, BitmapShard.chunkOf(userId));
        redis.opsForValue().setBit(bitmapKey, BitmapShard.bitOf(userId), true);
        assertThat(bitmapIndex.discoverCandidates(1))
                .contains(new CounterEntityIdentity("post", entityId));
        redis.delete(CounterKeys.bitmapShardIndexKey("like", "post", entityId));
        CounterCalibrationService calibration = new CounterCalibrationService(
                redis, redisson, counterPersistenceMapper, bitmapIndex, false, 50);

        assertThatThrownBy(() -> calibration.reconcileEntity("post", entityId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shard index");

        assertThat(redis.opsForValue().getBit(bitmapKey, BitmapShard.bitOf(userId))).isTrue();
        assertThat(redis.hasKey(CounterKeys.sdsKey("post", entityId))).isFalse();
        org.mockito.Mockito.verify(counterPersistenceMapper, org.mockito.Mockito.never())
                .replaceReactionSnapshots(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void missingShardIndexAndCandidateStillCannotEraseBitmapAuthority() {
        String entityId = "90010";
        long userId = 50L;
        String bitmapKey = CounterKeys.bitmapKey(
                "like", "post", entityId, BitmapShard.chunkOf(userId));
        redis.opsForValue().setBit(bitmapKey, BitmapShard.bitOf(userId), true);
        assertThat(bitmapIndex.discoverCandidates(1))
                .contains(new CounterEntityIdentity("post", entityId));
        redis.delete(CounterKeys.bitmapShardIndexKey("like", "post", entityId));
        redis.opsForZSet().remove(
                CounterKeys.bitmapCalibrationCandidatesKey(), "post:" + entityId);
        CounterCalibrationService calibration = new CounterCalibrationService(
                redis, redisson, counterPersistenceMapper, bitmapIndex, false, 50);

        assertThatThrownBy(() -> calibration.reconcileEntity("post", entityId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shard index");

        assertThat(redis.opsForValue().getBit(bitmapKey, BitmapShard.bitOf(userId))).isTrue();
        assertThat(redis.hasKey(CounterKeys.sdsKey("post", entityId))).isFalse();
    }

    @Test
    void missingSingleShardMemberCannotSilentlyReduceAuthoritativeCount() {
        String entityId = "90011";
        long firstUser = 51L;
        long secondUser = BitmapShard.CHUNK_SIZE + 52L;
        String firstKey = CounterKeys.bitmapKey(
                "like", "post", entityId, BitmapShard.chunkOf(firstUser));
        String secondKey = CounterKeys.bitmapKey(
                "like", "post", entityId, BitmapShard.chunkOf(secondUser));
        redis.opsForValue().setBit(firstKey, BitmapShard.bitOf(firstUser), true);
        redis.opsForValue().setBit(secondKey, BitmapShard.bitOf(secondUser), true);
        assertThat(bitmapIndex.discoverCandidates(1))
                .contains(new CounterEntityIdentity("post", entityId));
        redis.opsForSet().remove(
                CounterKeys.bitmapShardIndexKey("like", "post", entityId), secondKey);
        CounterCalibrationService calibration = new CounterCalibrationService(
                redis, redisson, counterPersistenceMapper, bitmapIndex, false, 50);

        assertThatThrownBy(() -> calibration.reconcileEntity("post", entityId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shard index");

        assertThat(redis.opsForValue().getBit(firstKey, BitmapShard.bitOf(firstUser))).isTrue();
        assertThat(redis.opsForValue().getBit(secondKey, BitmapShard.bitOf(secondUser))).isTrue();
        assertThat(redis.hasKey(CounterKeys.sdsKey("post", entityId))).isFalse();
    }

    @Test
    void managedMaintenanceTakesOverAStaleFenceWithoutExpiryUntilReconciliationCompletes() {
        long postId = 90_007L;
        long managedUser = 1L;
        long naturalUser = 5L;
        String fenceKey = CounterKeys.factMaintenanceFenceKey("post", String.valueOf(postId));
        redis.opsForValue().set(fenceKey, "stale-owner", 10L, TimeUnit.SECONDS);
        setBit("like", postId, naturalUser, true);
        AtomicLong ttlDuringReconciliation = new AtomicLong(Long.MIN_VALUE);
        when(userMapper.listExistingIds(anyList())).thenAnswer(invocation -> {
            Long ttl = redis.getExpire(fenceKey, TimeUnit.MILLISECONDS);
            ttlDuringReconciliation.set(ttl == null ? Long.MIN_VALUE : ttl);
            return List.of(naturalUser);
        });

        PostReactionReconciliationResult result = service.reconcileManagedPostReactions(
                Set.of(managedUser),
                Set.of(postId),
                Map.of(postId, new ManagedPostReactionState(Set.of(), Set.of())))
                .posts().get(postId);

        assertThat(result.likeTotal()).isEqualTo(1L);
        assertThat(result.favTotal()).isZero();
        assertThat(ttlDuringReconciliation.get()).isEqualTo(-1L);
        assertThat(redis.hasKey(fenceKey)).isFalse();
    }

    @Test
    void bitmapDiscoveryPersistsTheWholePageAndRotatesAcrossServiceInstances() {
        List<String> entityIds = List.of("91001", "91002", "91003");
        for (int index = 0; index < entityIds.size(); index++) {
            redis.opsForValue().setBit(
                    CounterKeys.bitmapKey("like", "post", entityIds.get(index), 0L),
                    index,
                    true);
        }

        List<CounterEntityIdentity> first = bitmapIndex.discoverCandidates(1);

        assertThat(first).hasSize(1);
        assertThat(redis.opsForZSet().zCard(CounterKeys.bitmapCalibrationCandidatesKey()))
                .isEqualTo(3L);
        assertThat(redis.opsForValue().get(CounterKeys.bitmapIndexBackfillCompleteKey()))
                .isEqualTo("v1");
        bitmapIndex.rotateCandidate(first.get(0));

        CounterBitmapIndexService restarted = new CounterBitmapIndexService(redis, 500);
        List<CounterEntityIdentity> second = restarted.discoverCandidates(1);

        assertThat(second).hasSize(1).doesNotContain(first.get(0));
        for (String entityId : entityIds) {
            assertThat(restarted.requireShardKeys("like", "post", entityId))
                    .containsExactly(CounterKeys.bitmapKey("like", "post", entityId, 0L));
        }
    }

    @Test
    void bitmapDiscoveryResumesANonZeroCursorAcrossServiceInstances() {
        for (int index = 0; index < 1_000; index++) {
            redis.opsForValue().set("discovery-filler:" + index, "1");
        }
        String entityId = "91004";
        String bitmapKey = CounterKeys.bitmapKey("like", "post", entityId, 0L);
        redis.opsForValue().setBit(bitmapKey, 7L, true);
        CounterBitmapIndexService firstInstance = new CounterBitmapIndexService(redis, 1);

        assertThat(firstInstance.discoverCandidates(1)).isEmpty();
        String firstCursor = redis.opsForValue().get(CounterKeys.bitmapIndexBackfillCursorKey());
        assertThat(firstCursor).isNotBlank().isNotEqualTo("0");
        assertThat(firstInstance.isBackfillComplete()).isFalse();

        CounterBitmapIndexService restarted = new CounterBitmapIndexService(redis, 1);
        restarted.discoverCandidates(1);
        assertThat(redis.opsForValue().get(CounterKeys.bitmapIndexBackfillCursorKey()))
                .isNotEqualTo(firstCursor);
        for (int page = 0; page < 5_000 && !restarted.isBackfillComplete(); page++) {
            restarted.discoverCandidates(1);
        }

        assertThat(restarted.isBackfillComplete()).isTrue();
        assertThat(restarted.requireShardKeys("like", "post", entityId))
                .containsExactly(bitmapKey);
    }

    @Test
    void repeatedReactionTargetsOnlyChangeBitmapSdsAndPublishOncePerTransition() {
        long postId = 90_005L;
        long userId = 45L;
        setRawString(CounterKeys.sdsKey("post", String.valueOf(postId)),
                new byte[CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE]);
        ConcurrentLinkedQueue<CounterEvent> events = new ConcurrentLinkedQueue<>();
        CounterServiceImpl counterService = new CounterServiceImpl(
                redis, events::add, mock(PostMapper.class), userMapper,
                new CounterCalibrationService(
                        redis, redisson, counterPersistenceMapper, bitmapIndex, false, 50));

        assertThat(counterService.like("post", String.valueOf(postId), userId)).isTrue();
        assertThat(counterService.like("post", String.valueOf(postId), userId)).isFalse();
        assertThat(counterService.fav("post", String.valueOf(postId), userId)).isTrue();
        assertThat(counterService.fav("post", String.valueOf(postId), userId)).isFalse();
        assertThat(events).hasSize(2);
        assertThat(bitCount("like", postId)).isEqualTo(1L);
        assertThat(bitCount("fav", postId)).isEqualTo(1L);
        assertThat(redis.opsForSet().members("bmidx:like:post:" + postId))
                .containsExactlyInAnyOrder("@v1", CounterKeys.bitmapKey(
                        "like", "post", String.valueOf(postId), BitmapShard.chunkOf(userId)));
        assertThat(redis.opsForSet().members("bmidx:fav:post:" + postId))
                .containsExactlyInAnyOrder("@v1", CounterKeys.bitmapKey(
                        "fav", "post", String.valueOf(postId), BitmapShard.chunkOf(userId)));
        assertThat(field(rawString(CounterKeys.sdsKey("post", String.valueOf(postId))),
                CounterSchema.IDX_LIKE)).containsExactly(unsignedInt32(1L));
        assertThat(field(rawString(CounterKeys.sdsKey("post", String.valueOf(postId))),
                CounterSchema.IDX_FAV)).containsExactly(unsignedInt32(1L));

        assertThat(counterService.unlike("post", String.valueOf(postId), userId)).isTrue();
        assertThat(counterService.unlike("post", String.valueOf(postId), userId)).isFalse();
        assertThat(counterService.unfav("post", String.valueOf(postId), userId)).isTrue();
        assertThat(counterService.unfav("post", String.valueOf(postId), userId)).isFalse();
        assertThat(events).hasSize(4);
        assertThat(bitCount("like", postId)).isZero();
        assertThat(bitCount("fav", postId)).isZero();
        assertThat(redis.opsForSet().members("bmidx:like:post:" + postId)).containsExactly("@v1");
        assertThat(redis.opsForSet().members("bmidx:fav:post:" + postId)).containsExactly("@v1");
        assertThat(field(rawString(CounterKeys.sdsKey("post", String.valueOf(postId))),
                CounterSchema.IDX_LIKE)).containsExactly(unsignedInt32(0L));
        assertThat(field(rawString(CounterKeys.sdsKey("post", String.valueOf(postId))),
                CounterSchema.IDX_FAV)).containsExactly(unsignedInt32(0L));
    }

    @Test
    void concurrentMixedReactionTargetsKeepBitmapSdsAndPublishedDeltasConsistent() throws Exception {
        long postId = 90_006L;
        long userId = 46L;
        setRawString(CounterKeys.sdsKey("post", String.valueOf(postId)),
                new byte[CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE]);
        ConcurrentLinkedQueue<CounterEvent> events = new ConcurrentLinkedQueue<>();
        CounterServiceImpl counterService = new CounterServiceImpl(
                redis, events::add, mock(PostMapper.class), userMapper,
                new CounterCalibrationService(
                        redis, redisson, counterPersistenceMapper, bitmapIndex, false, 50));
        int operationCount = 40;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> futures = new ArrayList<>(operationCount);
        try {
            for (int index = 0; index < operationCount; index++) {
                boolean add = index % 2 == 0;
                futures.add(executor.submit(() -> {
                    start.await();
                    return add
                            ? counterService.like("post", String.valueOf(postId), userId)
                            : counterService.unlike("post", String.valueOf(postId), userId);
                }));
            }
            start.countDown();
            long changed = 0L;
            for (Future<Boolean> future : futures) {
                if (future.get()) { changed++; }
            }

            long bitmapCount = bitCount("like", postId);
            long deltaSum = events.stream().mapToLong(CounterEvent::getDelta).sum();
            assertThat(bitmapCount).isIn(0L, 1L);
            assertThat(changed).isEqualTo(events.size());
            assertThat(deltaSum).isEqualTo(bitmapCount);
            assertThat(field(rawString(CounterKeys.sdsKey("post", String.valueOf(postId))),
                    CounterSchema.IDX_LIKE)).containsExactly(unsignedInt32(bitmapCount));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void lostFenceOwnershipAbortsBeforeEpochBitmapSdsOrAggregationWrites() {
        long postId = 90_004L;
        long managedUser = 1L;
        long naturalUser = 5L;
        String fenceKey = CounterKeys.factMaintenanceFenceKey("post", String.valueOf(postId));
        String countKey = CounterKeys.sdsKey("post", String.valueOf(postId));
        byte[] originalSds = initialSds();
        setRawString(countKey, originalSds);
        setBit("like", postId, naturalUser, true);
        when(userMapper.listExistingIds(anyList())).thenAnswer(invocation -> {
            redis.opsForValue().set(fenceKey, "other-owner");
            return List.of(naturalUser);
        });

        assertThatThrownBy(() -> service.reconcileManagedPostReactions(
                Set.of(managedUser), Set.of(postId),
                Map.of(postId, new ManagedPostReactionState(Set.of(managedUser), Set.of()))))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("fence ownership lost");

        assertThat(redis.opsForValue().get(fenceKey)).isEqualTo("other-owner");
        assertThat(redis.hasKey(CounterKeys.factEpochKey("post", String.valueOf(postId)))).isFalse();
        assertThat(getBit("like", postId, managedUser)).isFalse();
        assertThat(getBit("like", postId, naturalUser)).isTrue();
        assertThat(rawString(countKey)).containsExactly(originalSds);
    }

    private ReconciliationScenario seedScenario(long postId, long firstUserChunk) {
        long managedLikeUser = firstUserChunk * BitmapShard.CHUNK_SIZE + 11L;
        long managedFavUser = (firstUserChunk + 1L) * BitmapShard.CHUNK_SIZE + 12L;
        long naturalUser = (firstUserChunk + 2L) * BitmapShard.CHUNK_SIZE + 13L;
        long orphanUser = (firstUserChunk + 3L) * BitmapShard.CHUNK_SIZE + 14L;

        setBit("fav", postId, managedLikeUser, true);
        setBit("like", postId, managedFavUser, true);
        setBit("like", postId, naturalUser, true);
        setBit("fav", postId, naturalUser, true);
        setBit("like", postId, orphanUser, true);
        setBit("fav", postId, orphanUser, true);

        String countKey = CounterKeys.sdsKey("post", String.valueOf(postId));
        String aggregateKey = CounterKeys.aggKey("post", String.valueOf(postId));
        String unrelatedAggregateKey = CounterKeys.aggKey("post", String.valueOf(postId + 50_000L));
        byte[] initialSds = initialSds();
        setRawString(countKey, initialSds);
        redis.opsForHash().putAll(aggregateKey, Map.of("0", "17", "1", "-8", "2", "9"));
        redis.opsForSet().add(CounterKeys.aggIndexKey(), aggregateKey, unrelatedAggregateKey);

        when(userMapper.listExistingIds(anyList())).thenAnswer(invocation -> {
            List<Long> requested = invocation.getArgument(0);
            return requested.stream()
                    .filter(naturalUserId -> naturalUserId == naturalUser)
                    .toList();
        });

        return new ReconciliationScenario(
                postId,
                managedLikeUser,
                managedFavUser,
                naturalUser,
                orphanUser,
                countKey,
                aggregateKey,
                unrelatedAggregateKey,
                initialSds);
    }

    private WrongTypeScenario seedWrongTypeScenario(
            long postId, long userId, CoreWrongType wrongType) {
        String entityId = String.valueOf(postId);
        String countKey = CounterKeys.sdsKey("post", entityId);
        String aggregateKey = CounterKeys.aggKey("post", entityId);
        String likeKey = bitmapKey("like", postId, userId);
        String favKey = bitmapKey("fav", postId, userId);
        setBit("fav", postId, userId, true);
        setRawString(countKey, initialSds());
        redis.opsForHash().putAll(aggregateKey, Map.of("0", "5", "1", "1", "2", "-1"));
        redis.opsForSet().add(CounterKeys.aggIndexKey(), aggregateKey);

        if (wrongType == CoreWrongType.COUNT) {
            redis.delete(countKey);
            redis.opsForHash().put(countKey, "wrong", "type");
        } else {
            redis.delete(aggregateKey);
            redis.opsForValue().set(aggregateKey, "wrong-type");
        }

        return new WrongTypeScenario(
                postId,
                userId,
                BitmapShard.bitOf(userId),
                countKey,
                aggregateKey,
                likeKey,
                favKey);
    }

    private PostReactionReconciliationResult reconcile(ReconciliationScenario scenario) {
        return service.reconcileManagedPostReactions(
                        Set.of(scenario.managedLikeUser(), scenario.managedFavUser()),
                        Set.of(scenario.postId()),
                        Map.of(scenario.postId(), new ManagedPostReactionState(
                                Set.of(scenario.managedLikeUser()),
                                Set.of(scenario.managedFavUser()))))
                .posts()
                .get(scenario.postId());
    }

    private static void assertDesiredBits(ReconciliationScenario scenario) {
        assertThat(getBit("like", scenario.postId(), scenario.managedLikeUser())).isTrue();
        assertThat(getBit("fav", scenario.postId(), scenario.managedLikeUser())).isFalse();
        assertThat(getBit("like", scenario.postId(), scenario.managedFavUser())).isFalse();
        assertThat(getBit("fav", scenario.postId(), scenario.managedFavUser())).isTrue();
        assertThat(getBit("like", scenario.postId(), scenario.naturalUser())).isTrue();
        assertThat(getBit("fav", scenario.postId(), scenario.naturalUser())).isTrue();
        assertThat(getBit("like", scenario.postId(), scenario.orphanUser())).isFalse();
        assertThat(getBit("fav", scenario.postId(), scenario.orphanUser())).isFalse();
    }

    private static long bitCount(String metric, long postId) {
        Set<String> bitmapKeys = bitmapKeys(metric, postId);
        Long result = redis.execute((RedisCallback<Long>) connection -> {
            long total = 0L;
            for (String key : bitmapKeys) {
                Long shardCount = connection.stringCommands().bitCount(bytes(key));
                total += shardCount == null ? 0L : shardCount;
            }
            return total;
        });
        return result == null ? 0L : result;
    }

    private static Set<String> bitmapKeys(String metric, long postId) {
        Set<String> bitmapKeys = new LinkedHashSet<>();
        ScanOptions scanOptions = ScanOptions.scanOptions()
                .match("bm:" + metric + ":post:" + postId + ":*")
                .count(100)
                .build();
        try (Cursor<String> cursor = redis.scan(scanOptions)) {
            while (cursor.hasNext()) {
                bitmapKeys.add(cursor.next());
            }
        }
        return Set.copyOf(bitmapKeys);
    }

    private static RedisStateSnapshot snapshot(Set<String> keys) {
        Map<String, RedisValueSnapshot> values = new LinkedHashMap<>();
        for (String key : keys) {
            DataType type = redis.type(key);
            byte[] dump = redis.dump(key);
            values.put(key, new RedisValueSnapshot(
                    Objects.requireNonNull(type, "Redis TYPE returned null").code(),
                    dump == null ? null : Base64.getEncoder().encodeToString(dump)));
        }
        return new RedisStateSnapshot(Map.copyOf(values));
    }

    private static byte[] initialSds() {
        byte[] raw = new byte[CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE];
        writeUnsignedInt32(raw, CounterSchema.IDX_VIEW, 0x0102_0304L);
        writeUnsignedInt32(raw, CounterSchema.IDX_LIKE, 99L);
        writeUnsignedInt32(raw, CounterSchema.IDX_FAV, 88L);
        writeUnsignedInt32(raw, 3, 0x1122_3344L);
        writeUnsignedInt32(raw, 4, 0x5566_7788L);
        return raw;
    }

    private static void writeUnsignedInt32(byte[] raw, int index, long value) {
        byte[] encoded = unsignedInt32(value);
        System.arraycopy(encoded, 0, raw, index * CounterSchema.FIELD_SIZE, encoded.length);
    }

    private static byte[] unsignedInt32(long value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    private static byte[] field(byte[] raw, int index) {
        int start = index * CounterSchema.FIELD_SIZE;
        return Arrays.copyOfRange(raw, start, start + CounterSchema.FIELD_SIZE);
    }

    private static void setRawString(String key, byte[] value) {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.stringCommands().set(bytes(key), value);
            return null;
        });
    }

    private static byte[] rawString(String key) {
        return redis.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(bytes(key)));
    }

    private static void setBit(String metric, long postId, long userId, boolean value) {
        String key = bitmapKey(metric, postId, userId);
        redis.execute((RedisCallback<Void>) connection -> {
            connection.stringCommands().setBit(bytes(key), BitmapShard.bitOf(userId), value);
            return null;
        });
    }

    private static boolean getBit(String metric, long postId, long userId) {
        return getBit(bitmapKey(metric, postId, userId), BitmapShard.bitOf(userId));
    }

    private static boolean getBit(String key, long bitOffset) {
        Boolean value = redis.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().getBit(bytes(key), bitOffset));
        return Boolean.TRUE.equals(value);
    }

    private static String bitmapKey(String metric, long postId, long userId) {
        return CounterKeys.bitmapKey(
                metric, "post", String.valueOf(postId), BitmapShard.chunkOf(userId));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void closeRedisResources() {
        try {
            if (redisson != null) {
                redisson.shutdown();
            }
        } finally {
            try {
                if (lettuce != null) {
                    lettuce.destroy();
                }
            } finally {
                if (REDIS_CONTAINER.isRunning()) {
                    REDIS_CONTAINER.stop();
                }
            }
        }
    }

    private enum CoreWrongType {
        COUNT,
        AGGREGATE
    }

    private record ReconciliationScenario(
            long postId,
            long managedLikeUser,
            long managedFavUser,
            long naturalUser,
            long orphanUser,
            String countKey,
            String aggregateKey,
            String unrelatedAggregateKey,
            byte[] initialSds) {

        private Set<String> trackedKeys() {
            Set<String> keys = new LinkedHashSet<>();
            keys.add(countKey);
            keys.add(aggregateKey);
            keys.add(CounterKeys.aggIndexKey());
            for (String metric : List.of("like", "fav")) {
                for (long userId : List.of(
                        managedLikeUser, managedFavUser, naturalUser, orphanUser)) {
                    keys.add(bitmapKey(metric, postId, userId));
                }
            }
            return Set.copyOf(keys);
        }
    }

    private record WrongTypeScenario(
            long postId,
            long userId,
            long bitOffset,
            String countKey,
            String aggregateKey,
            String likeKey,
            String favKey) {

        private Set<String> trackedKeys() {
            return Set.of(countKey, aggregateKey, CounterKeys.aggIndexKey(), likeKey, favKey);
        }
    }

    private record RedisStateSnapshot(Map<String, RedisValueSnapshot> values) {}

    private record RedisValueSnapshot(String type, String dump) {}
}
