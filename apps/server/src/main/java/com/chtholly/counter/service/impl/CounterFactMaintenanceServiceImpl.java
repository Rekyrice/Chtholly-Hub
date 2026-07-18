package com.chtholly.counter.service.impl;

import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.service.CounterFactMaintenanceService;
import com.chtholly.user.mapper.UserMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed maintenance service for exact historical post reaction reconciliation.
 *
 * <p>Each authoritative post is fenced against interactive bitmap writes before its bitmap snapshot
 * and ID-only MySQL existence query. One final Lua script validates fence ownership, advances the
 * fact epoch, reconciles bits and exact counts, and discards stale reaction deltas atomically.
 * Completed posts remain committed if a later post fails.
 */
@Service
public class CounterFactMaintenanceServiceImpl implements CounterFactMaintenanceService {

    private static final int USER_QUERY_BATCH_SIZE = 500;
    private static final String ENTITY_TYPE_POST = "post";
    private static final String METRIC_LIKE = "like";
    private static final String METRIC_FAV = "fav";
    private static final String MUTATION_MANAGED = "managed";
    private static final String MUTATION_ORPHAN = "orphan";
    private static final int LUA_RESULT_SIZE = 5;
    private static final int LUA_CORE_KEY_COUNT = 5;
    private static final long FENCE_LEASE_MILLIS = 60_000L;

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final UserMapper userMapper;
    private final DefaultRedisScript<List> reconciliationScript;
    private final DefaultRedisScript<Long> acquireFenceScript;
    private final DefaultRedisScript<Long> releaseFenceScript;

    /**
     * Creates the maintenance service.
     *
     * @param redis string-keyed Redis access used with raw binary callbacks for bitmaps
     * @param redisson distributed lock provider
     * @param userMapper authoritative MySQL user lookup
     */
    public CounterFactMaintenanceServiceImpl(
            StringRedisTemplate redis,
            RedissonClient redisson,
            UserMapper userMapper) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        this.userMapper = Objects.requireNonNull(userMapper, "userMapper");
        this.reconciliationScript = new DefaultRedisScript<>();
        this.reconciliationScript.setResultType(List.class);
        this.reconciliationScript.setScriptText(RECONCILE_POST_REACTIONS_LUA);
        this.acquireFenceScript = new DefaultRedisScript<>();
        this.acquireFenceScript.setResultType(Long.class);
        this.acquireFenceScript.setScriptText(ACQUIRE_FENCE_LUA);
        this.releaseFenceScript = new DefaultRedisScript<>();
        this.releaseFenceScript.setResultType(Long.class);
        this.releaseFenceScript.setScriptText(RELEASE_FENCE_LUA);
    }

    /** {@inheritDoc} */
    @Override
    public ReactionReconciliationResult reconcileManagedPostReactions(
            Set<Long> managedUserIds,
            Set<Long> authoritativePostIds,
            Map<Long, ManagedPostReactionState> desiredByPost) {
        ValidatedRequest request = validateAndCopyInput(
                managedUserIds, authoritativePostIds, desiredByPost);

        Map<Long, PostReactionReconciliationResult> results = new LinkedHashMap<>();
        for (Long postId : request.authoritativePostIds()) {
            ManagedPostReactionState desired = request.desiredByPost().getOrDefault(
                    postId, EmptyDesiredState.VALUE);
            PostReactionReconciliationResult result = reconcilePost(
                    postId,
                    request.managedUserIds(),
                    desired);
            results.put(postId, result);
        }
        return new ReactionReconciliationResult(results);
    }

    static Set<Long> decodeSetUserIds(long chunk, byte[] raw) {
        if (chunk < 0L) {
            throw new IllegalArgumentException("chunk must be non-negative");
        }
        Objects.requireNonNull(raw, "raw");
        int maximumBytes = BitmapShard.CHUNK_SIZE / Byte.SIZE;
        if (raw.length > maximumBytes) {
            throw new IllegalStateException("Bitmap value exceeds the configured shard size");
        }
        Set<Long> userIds = new LinkedHashSet<>();
        long chunkBase;
        try {
            chunkBase = Math.multiplyExact(chunk, (long) BitmapShard.CHUNK_SIZE);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Bitmap chunk exceeds the supported user ID range", exception);
        }
        for (int byteIndex = 0; byteIndex < raw.length; byteIndex++) {
            int value = raw[byteIndex] & 0xFF;
            if (value == 0) {
                continue;
            }
            for (int bitInByte = 0; bitInByte < Byte.SIZE; bitInByte++) {
                if ((value & (1 << (7 - bitInByte))) == 0) {
                    continue;
                }
                long bitOffset = (long) byteIndex * Byte.SIZE + bitInByte;
                try {
                    userIds.add(Math.addExact(chunkBase, bitOffset));
                } catch (ArithmeticException exception) {
                    throw new IllegalStateException("Bitmap bit exceeds the supported user ID range", exception);
                }
            }
        }
        return Set.copyOf(userIds);
    }

    private PostBitmapSnapshot snapshotPost(long postId) {
        PostBitmapSnapshot snapshot = new PostBitmapSnapshot();
        snapshotMetric(postId, METRIC_LIKE, snapshot);
        snapshotMetric(postId, METRIC_FAV, snapshot);
        return snapshot;
    }

    private void snapshotMetric(long postId, String metric, PostBitmapSnapshot snapshot) {
        String pattern = String.format("bm:%s:%s:%d:*", metric, ENTITY_TYPE_POST, postId);
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        Set<String> shardKeys = new LinkedHashSet<>();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                shardKeys.add(cursor.next());
            }
        }

        for (String shardKey : shardKeys) {
            long chunk = parseChunk(shardKey, metric, postId);
            snapshot.addShard(shardKey, metric);
            byte[] raw = redis.execute((RedisCallback<byte[]>) connection ->
                    connection.stringCommands().get(shardKey.getBytes(StandardCharsets.UTF_8)));
            if (raw == null) {
                continue;
            }
            for (Long userId : decodeSetUserIds(chunk, raw)) {
                long bitOffset = Math.subtractExact(
                        userId, Math.multiplyExact(chunk, (long) BitmapShard.CHUNK_SIZE));
                snapshot.addSetBit(userId, new BitmapBit(shardKey, bitOffset));
            }
        }
    }

    private static long parseChunk(String shardKey, String metric, long postId) {
        String prefix = String.format("bm:%s:%s:%d:", metric, ENTITY_TYPE_POST, postId);
        if (shardKey == null || !shardKey.startsWith(prefix)) {
            throw new IllegalStateException("SCAN returned a bitmap key outside the requested post");
        }
        String chunkText = shardKey.substring(prefix.length());
        try {
            long chunk = Long.parseLong(chunkText);
            if (chunk < 0L || !CounterKeys.bitmapKey(
                    metric, ENTITY_TYPE_POST, String.valueOf(postId), chunk).equals(shardKey)) {
                throw new IllegalStateException("Invalid bitmap shard key: " + shardKey);
            }
            return chunk;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid bitmap shard key: " + shardKey, exception);
        }
    }

    private Set<Long> findExistingNaturalUsers(
            Collection<PostBitmapSnapshot> snapshots,
            Set<Long> managedUserIds) {
        TreeSet<Long> candidates = new TreeSet<>();
        for (PostBitmapSnapshot snapshot : snapshots) {
            for (Long userId : snapshot.setBitsByUser().keySet()) {
                if (userId > 0L && !managedUserIds.contains(userId)) {
                    candidates.add(userId);
                }
            }
        }
        if (candidates.isEmpty()) {
            return Set.of();
        }

        List<Long> orderedCandidates = List.copyOf(candidates);
        Set<Long> existing = new LinkedHashSet<>();
        for (int start = 0; start < orderedCandidates.size(); start += USER_QUERY_BATCH_SIZE) {
            int end = Math.min(start + USER_QUERY_BATCH_SIZE, orderedCandidates.size());
            List<Long> batch = List.copyOf(orderedCandidates.subList(start, end));
            List<Long> userIds = userMapper.listExistingIds(batch);
            if (userIds == null) {
                throw new IllegalStateException("UserMapper returned null for an existence query");
            }
            Set<Long> requested = Set.copyOf(batch);
            for (Long userId : userIds) {
                if (userId == null || !requested.contains(userId)) {
                    throw new IllegalStateException("UserMapper returned an invalid existence result");
                }
                existing.add(userId);
            }
        }
        return Set.copyOf(existing);
    }

    private PostReactionReconciliationResult reconcilePost(
            long postId,
            Set<Long> managedUserIds,
            ManagedPostReactionState desired) {
        RLock lock = redisson.getLock(CounterKeys.factMaintenanceLockKey(
                ENTITY_TYPE_POST, String.valueOf(postId)));
        boolean locked = false;
        boolean fenced = false;
        String fenceToken = UUID.randomUUID().toString();
        Throwable primaryFailure = null;
        try {
            locked = lock.tryLock(0L, TimeUnit.MILLISECONDS);
            if (!locked) {
                throw new IllegalStateException(
                        "Could not acquire counter fact maintenance lock for post " + postId);
            }
            fenced = acquireFence(postId, fenceToken);
            if (!fenced) {
                throw new IllegalStateException(
                        "Could not acquire counter fact maintenance fence for post " + postId);
            }
            PostBitmapSnapshot snapshot = snapshotPost(postId);
            Set<Long> existingNaturalUsers = findExistingNaturalUsers(
                    List.of(snapshot), managedUserIds);
            LuaPlan plan = buildLuaPlan(
                    postId, managedUserIds, desired, snapshot, existingNaturalUsers, fenceToken);
            List<?> rawResult = redis.execute(
                    reconciliationScript, plan.keys(), plan.arguments().toArray());
            return mapLuaResult(postId, rawResult);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            IllegalStateException wrapped = new IllegalStateException(
                    "Interrupted while acquiring counter fact maintenance lock for post " + postId,
                    exception);
            primaryFailure = wrapped;
            throw wrapped;
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            cleanupMaintenanceOwnership(postId, lock, locked, fenceToken, fenced, primaryFailure);
        }
    }

    private boolean acquireFence(long postId, String token) {
        Long acquired = redis.execute(
                acquireFenceScript,
                List.of(CounterKeys.factMaintenanceFenceKey(ENTITY_TYPE_POST, String.valueOf(postId))),
                token,
                String.valueOf(FENCE_LEASE_MILLIS));
        return Long.valueOf(1L).equals(acquired);
    }

    private void cleanupMaintenanceOwnership(
            long postId,
            RLock lock,
            boolean locked,
            String fenceToken,
            boolean fenced,
            Throwable primaryFailure) {
        RuntimeException cleanupFailure = null;
        if (fenced) {
            try {
                redis.execute(
                        releaseFenceScript,
                        List.of(CounterKeys.factMaintenanceFenceKey(
                                ENTITY_TYPE_POST, String.valueOf(postId))),
                        fenceToken);
            } catch (RuntimeException failure) {
                cleanupFailure = failure;
            }
        }
        if (locked) {
            try {
                lock.unlock();
            } catch (RuntimeException failure) {
                if (cleanupFailure == null) {
                    cleanupFailure = failure;
                } else {
                    cleanupFailure.addSuppressed(failure);
                }
            }
        }
        if (cleanupFailure != null) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            } else {
                throw cleanupFailure;
            }
        }
    }

    private static LuaPlan buildLuaPlan(
            long postId,
            Set<Long> managedUserIds,
            ManagedPostReactionState desired,
            PostBitmapSnapshot snapshot,
            Set<Long> existingNaturalUsers,
            String fenceToken) {
        LinkedHashMap<String, String> bitmapMetrics = new LinkedHashMap<>(snapshot.shardMetrics());
        List<Long> orderedManagedUsers = managedUserIds.stream().sorted().toList();
        for (Long userId : orderedManagedUsers) {
            addManagedShard(bitmapMetrics, postId, METRIC_LIKE, userId);
            addManagedShard(bitmapMetrics, postId, METRIC_FAV, userId);
        }

        List<String> keys = new ArrayList<>();
        String aggregateKey = CounterKeys.aggKey(ENTITY_TYPE_POST, String.valueOf(postId));
        keys.add(CounterKeys.sdsKey(ENTITY_TYPE_POST, String.valueOf(postId)));
        keys.add(aggregateKey);
        keys.add(CounterKeys.aggIndexKey());
        keys.add(CounterKeys.factMaintenanceFenceKey(ENTITY_TYPE_POST, String.valueOf(postId)));
        keys.add(CounterKeys.factEpochKey(ENTITY_TYPE_POST, String.valueOf(postId)));
        keys.addAll(bitmapMetrics.keySet());

        Map<String, Integer> luaKeyIndexes = new LinkedHashMap<>();
        for (int index = LUA_CORE_KEY_COUNT; index < keys.size(); index++) {
            luaKeyIndexes.put(keys.get(index), index + 1);
        }

        List<String> arguments = new ArrayList<>();
        arguments.add(fenceToken);
        arguments.add(String.valueOf(CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE));
        arguments.add(String.valueOf(CounterSchema.FIELD_SIZE));
        arguments.add(String.valueOf(CounterSchema.IDX_LIKE));
        arguments.add(String.valueOf(CounterSchema.IDX_FAV));
        arguments.add(String.valueOf(bitmapMetrics.size()));
        arguments.addAll(bitmapMetrics.values());

        List<BitMutation> mutations = new ArrayList<>();
        for (Long userId : orderedManagedUsers) {
            addManagedMutation(
                    mutations, luaKeyIndexes, postId, METRIC_LIKE, userId,
                    desired.likedUserIds().contains(userId));
            addManagedMutation(
                    mutations, luaKeyIndexes, postId, METRIC_FAV, userId,
                    desired.favedUserIds().contains(userId));
        }
        snapshot.setBitsByUser().entrySet().stream()
                .filter(entry -> !managedUserIds.contains(entry.getKey()))
                .filter(entry -> entry.getKey() <= 0L || !existingNaturalUsers.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .sorted(Comparator.comparing(BitmapBit::key).thenComparingLong(BitmapBit::bitOffset))
                .forEach(bit -> mutations.add(new BitMutation(
                        luaKeyIndexes.get(bit.key()), bit.bitOffset(), false, MUTATION_ORPHAN)));

        arguments.add(String.valueOf(mutations.size()));
        for (BitMutation mutation : mutations) {
            arguments.add(String.valueOf(mutation.luaKeyIndex()));
            arguments.add(String.valueOf(mutation.bitOffset()));
            arguments.add(mutation.targetSet() ? "1" : "0");
            arguments.add(mutation.kind());
        }
        return new LuaPlan(List.copyOf(keys), List.copyOf(arguments));
    }

    private static void addManagedShard(
            Map<String, String> bitmapMetrics, long postId, String metric, long userId) {
        String key = CounterKeys.bitmapKey(
                metric,
                ENTITY_TYPE_POST,
                String.valueOf(postId),
                BitmapShard.chunkOf(userId));
        bitmapMetrics.putIfAbsent(key, metric);
    }

    private static void addManagedMutation(
            List<BitMutation> mutations,
            Map<String, Integer> luaKeyIndexes,
            long postId,
            String metric,
            long userId,
            boolean targetSet) {
        String key = CounterKeys.bitmapKey(
                metric,
                ENTITY_TYPE_POST,
                String.valueOf(postId),
                BitmapShard.chunkOf(userId));
        mutations.add(new BitMutation(
                luaKeyIndexes.get(key), BitmapShard.bitOf(userId), targetSet, MUTATION_MANAGED));
    }

    private static PostReactionReconciliationResult mapLuaResult(long postId, List<?> values) {
        if (values == null || values.size() != LUA_RESULT_SIZE) {
            throw new IllegalStateException("Counter fact maintenance Lua returned an invalid result");
        }
        long managedSet = numericResult(values.get(0));
        long managedClear = numericResult(values.get(1));
        long orphanClear = numericResult(values.get(2));
        long likeTotal = numericResult(values.get(3));
        long favTotal = numericResult(values.get(4));
        return new PostReactionReconciliationResult(
                postId, managedSet, managedClear, orphanClear, likeTotal, favTotal);
    }

    private static long numericResult(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Counter fact maintenance Lua returned a non-numeric value");
        }
        return number.longValue();
    }

    private static ValidatedRequest validateAndCopyInput(
            Set<Long> managedUserIds,
            Set<Long> authoritativePostIds,
            Map<Long, ManagedPostReactionState> desiredByPost) {
        Objects.requireNonNull(managedUserIds, "managedUserIds");
        Objects.requireNonNull(authoritativePostIds, "authoritativePostIds");
        Objects.requireNonNull(desiredByPost, "desiredByPost");
        requirePositiveIds(managedUserIds, "managedUserIds");
        requirePositiveIds(authoritativePostIds, "authoritativePostIds");
        Set<Long> managed = Set.copyOf(managedUserIds);
        TreeSet<Long> orderedPosts = new TreeSet<>(authoritativePostIds);
        Map<Long, ManagedPostReactionState> desired = Map.copyOf(desiredByPost);
        if (managed.isEmpty()) {
            throw new IllegalArgumentException("managedUserIds must not be empty");
        }
        if (orderedPosts.isEmpty()) {
            throw new IllegalArgumentException("authoritativePostIds must not be empty");
        }
        for (Map.Entry<Long, ManagedPostReactionState> entry : desired.entrySet()) {
            Long postId = entry.getKey();
            if (postId == null || !orderedPosts.contains(postId)) {
                throw new IllegalArgumentException("desired post must belong to authoritativePostIds");
            }
            ManagedPostReactionState state = Objects.requireNonNull(entry.getValue(), "desired state");
            requireManagedIds(state.likedUserIds(), managed);
            requireManagedIds(state.favedUserIds(), managed);
        }
        return new ValidatedRequest(managed, List.copyOf(orderedPosts), desired);
    }

    private static void requirePositiveIds(Collection<Long> ids, String name) {
        if (ids.stream().anyMatch(id -> id == null || id <= 0L)) {
            throw new IllegalArgumentException(name + " must contain only positive IDs");
        }
    }

    private static void requireManagedIds(Set<Long> desiredIds, Set<Long> managedUserIds) {
        requirePositiveIds(desiredIds, "desired reaction users");
        if (!managedUserIds.containsAll(desiredIds)) {
            throw new IllegalArgumentException("desired reaction users must belong to managedUserIds");
        }
    }

    private static final class EmptyDesiredState {
        private static final ManagedPostReactionState VALUE =
                new ManagedPostReactionState(Set.of(), Set.of());

        private EmptyDesiredState() {}
    }

    private record ValidatedRequest(
            Set<Long> managedUserIds,
            List<Long> authoritativePostIds,
            Map<Long, ManagedPostReactionState> desiredByPost) {}

    private record BitmapBit(String key, long bitOffset) {}

    private record BitMutation(
            int luaKeyIndex, long bitOffset, boolean targetSet, String kind) {}

    private record LuaPlan(List<String> keys, List<String> arguments) {}

    private static final class PostBitmapSnapshot {
        private final Map<String, String> shardMetrics = new LinkedHashMap<>();
        private final Map<Long, List<BitmapBit>> setBitsByUser = new LinkedHashMap<>();

        private void addShard(String key, String metric) {
            String previous = shardMetrics.putIfAbsent(key, metric);
            if (previous != null && !previous.equals(metric)) {
                throw new IllegalStateException("Bitmap shard was associated with multiple metrics");
            }
        }

        private void addSetBit(long userId, BitmapBit bit) {
            setBitsByUser.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(bit);
        }

        private Map<String, String> shardMetrics() {
            return shardMetrics;
        }

        private Map<Long, List<BitmapBit>> setBitsByUser() {
            return setBitsByUser;
        }
    }

    private static final String RECONCILE_POST_REACTIONS_LUA = """
            local cntKey = KEYS[1]
            local aggKey = KEYS[2]
            local aggIndexKey = KEYS[3]
            local fenceKey = KEYS[4]
            local epochKey = KEYS[5]
            local expectedToken = ARGV[1]
            local expectedLength = tonumber(ARGV[2])
            local fieldSize = tonumber(ARGV[3])
            local likeIndex = tonumber(ARGV[4])
            local favIndex = tonumber(ARGV[5])
            local bitmapKeyCount = tonumber(ARGV[6])
            local argumentIndex = 7
            local bitmapKeyOffset = 5
            local uint32Max = 4294967295
            local function keyType(key)
              local reply = redis.call('TYPE', key)
              if type(reply) == 'table' then
                return reply['ok']
              end
              return reply
            end
            local cntType = keyType(cntKey)
            local aggType = keyType(aggKey)
            local aggIndexType = keyType(aggIndexKey)
            local fenceType = keyType(fenceKey)
            local epochType = keyType(epochKey)
            if (cntType ~= 'none' and cntType ~= 'string')
                  or (aggType ~= 'none' and aggType ~= 'hash')
                  or (aggIndexType ~= 'none' and aggIndexType ~= 'set')
                  or fenceType ~= 'string'
                  or (epochType ~= 'none' and epochType ~= 'string') then
              return redis.error_reply('counter core key has an invalid Redis type')
            end
            if redis.call('GET', fenceKey) ~= expectedToken then
              return redis.error_reply('counter fact maintenance fence ownership lost')
            end
            local currentEpoch = tonumber(redis.call('GET', epochKey) or '0')
            if not currentEpoch or currentEpoch < 0 or currentEpoch ~= math.floor(currentEpoch) then
              return redis.error_reply('counter fact epoch is invalid')
            end
            if expectedLength ~= 20 or fieldSize ~= 4
                  or likeIndex ~= 1 or favIndex ~= 2 then
              return redis.error_reply('counter schema arguments are invalid')
            end
            if not bitmapKeyCount or bitmapKeyCount < 0
                  or bitmapKeyCount ~= math.floor(bitmapKeyCount)
                  or bitmapKeyCount ~= (#KEYS - bitmapKeyOffset) then
              return redis.error_reply('counter bitmap key count is invalid')
            end
            local raw = redis.call('GET', cntKey)
            if not raw or string.len(raw) ~= expectedLength then
              raw = string.rep(string.char(0), expectedLength)
            end
            local bitmapMetrics = {}
            for bitmapIndex = 1, bitmapKeyCount do
              local metric = ARGV[argumentIndex]
              if metric ~= 'like' and metric ~= 'fav' then
                return redis.error_reply('counter bitmap metric is invalid')
              end
              bitmapMetrics[bitmapIndex] = metric
              argumentIndex = argumentIndex + 1
            end

            local mutationCount = tonumber(ARGV[argumentIndex])
            argumentIndex = argumentIndex + 1
            if not mutationCount or mutationCount < 0
                  or mutationCount ~= math.floor(mutationCount)
                  or #ARGV ~= 7 + bitmapKeyCount + mutationCount * 4 then
              return redis.error_reply('counter bitmap mutation count is invalid')
            end
            local changes = {}
            local projectedDeltaByKey = {}
            local managedSet = 0
            local managedClear = 0
            local orphanClear = 0
            for mutationIndex = 1, mutationCount do
              local keyIndex = tonumber(ARGV[argumentIndex])
              local bitOffset = tonumber(ARGV[argumentIndex + 1])
              local target = tonumber(ARGV[argumentIndex + 2])
              local kind = ARGV[argumentIndex + 3]
              argumentIndex = argumentIndex + 4
              if not keyIndex or keyIndex <= bitmapKeyOffset or keyIndex > #KEYS
                    or keyIndex ~= math.floor(keyIndex)
                    or not bitOffset or bitOffset < 0 or bitOffset >= 32768
                    or bitOffset ~= math.floor(bitOffset)
                    or (target ~= 0 and target ~= 1)
                    or (kind ~= 'managed' and kind ~= 'orphan')
                    or (kind == 'orphan' and target ~= 0) then
                return redis.error_reply('counter bitmap mutation is invalid')
              end
              local previous = redis.call('GETBIT', KEYS[keyIndex], bitOffset)
              if previous ~= target then
                table.insert(changes, {keyIndex, bitOffset, target})
                projectedDeltaByKey[keyIndex] = (projectedDeltaByKey[keyIndex] or 0) + target - previous
                if kind == 'orphan' then
                  orphanClear = orphanClear + 1
                elseif target == 1 then
                  managedSet = managedSet + 1
                else
                  managedClear = managedClear + 1
                end
              end
            end

            local likeTotal = 0
            local favTotal = 0
            local projectedCountByKey = {}
            for bitmapIndex = 1, bitmapKeyCount do
              local keyIndex = bitmapIndex + bitmapKeyOffset
              local bitmapKey = KEYS[keyIndex]
              local projectedCount = redis.call('BITCOUNT', bitmapKey)
                    + (projectedDeltaByKey[keyIndex] or 0)
              projectedCountByKey[keyIndex] = projectedCount
              if bitmapMetrics[bitmapIndex] == 'like' then
                likeTotal = likeTotal + projectedCount
              else
                favTotal = favTotal + projectedCount
              end
            end
            if likeTotal > uint32Max or favTotal > uint32Max then
              return redis.error_reply('counter total exceeds unsigned Int32')
            end

            local nextEpoch = redis.call('INCR', epochKey)
            if nextEpoch ~= currentEpoch + 1 then
              return redis.error_reply('counter fact epoch changed unexpectedly')
            end
            for _, change in ipairs(changes) do
              redis.call('SETBIT', KEYS[change[1]], change[2], change[3])
            end
            for bitmapIndex = 1, bitmapKeyCount do
              local keyIndex = bitmapIndex + bitmapKeyOffset
              local bitmapKey = KEYS[keyIndex]
              if projectedCountByKey[keyIndex] == 0 then
                redis.call('DEL', bitmapKey)
              end
            end

            local function encodeUnsignedInt32(value)
              local b1 = math.floor(value / 16777216) % 256
              local b2 = math.floor(value / 65536) % 256
              local b3 = math.floor(value / 256) % 256
              local b4 = value % 256
              return string.char(b1, b2, b3, b4)
            end
            local function replaceUnsignedInt32(value, index, fieldValue)
              local offset = index * fieldSize
              return string.sub(value, 1, offset)
                    .. encodeUnsignedInt32(fieldValue)
                    .. string.sub(value, offset + fieldSize + 1)
            end
            raw = replaceUnsignedInt32(raw, likeIndex, likeTotal)
            raw = replaceUnsignedInt32(raw, favIndex, favTotal)
            redis.call('SET', cntKey, raw)
            redis.call('HDEL', aggKey, tostring(likeIndex), tostring(favIndex))
            if redis.call('HLEN', aggKey) == 0 then
              redis.call('DEL', aggKey)
              redis.call('SREM', aggIndexKey, aggKey)
            end
            return {managedSet, managedClear, orphanClear, likeTotal, favTotal}
            """;

    private static final String ACQUIRE_FENCE_LUA = """
            local fenceKey = KEYS[1]
            local token = ARGV[1]
            local leaseMillis = tonumber(ARGV[2])
            local typeReply = redis.call('TYPE', fenceKey)
            local fenceType = type(typeReply) == 'table' and typeReply['ok'] or typeReply
            if fenceType ~= 'none' and fenceType ~= 'string' then
              return redis.error_reply('counter fact maintenance fence has an invalid Redis type')
            end
            if not token or token == '' or not leaseMillis or leaseMillis <= 0 then
              return redis.error_reply('counter fact maintenance fence arguments are invalid')
            end
            if not redis.call('SET', fenceKey, token, 'NX', 'PX', leaseMillis) then return 0 end
            return 1
            """;

    private static final String RELEASE_FENCE_LUA = """
            local fenceKey = KEYS[1]
            local token = ARGV[1]
            local typeReply = redis.call('TYPE', fenceKey)
            local fenceType = type(typeReply) == 'table' and typeReply['ok'] or typeReply
            if fenceType == 'none' then return 0 end
            if fenceType ~= 'string' then
              return redis.error_reply('counter fact maintenance fence has an invalid Redis type')
            end
            if redis.call('GET', fenceKey) ~= token then return 0 end
            return redis.call('DEL', fenceKey)
            """;
}
