package com.chtholly.counter.service.impl;

import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Restores reaction counts from authoritative Bitmap shards into the fixed Redis counter. */
@Service
public class CounterCalibrationService {

    private static final long FENCE_LEASE_MILLIS = 60_000L;
    private static final long UINT32_MAX = 0xffff_ffffL;

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final DefaultRedisScript<Long> acquireFenceScript;
    private final DefaultRedisScript<Long> releaseFenceScript;
    private final DefaultRedisScript<List> reconcileScript;

    public CounterCalibrationService(StringRedisTemplate redis, RedissonClient redisson) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        this.acquireFenceScript = new DefaultRedisScript<>(ACQUIRE_FENCE_LUA, Long.class);
        this.releaseFenceScript = new DefaultRedisScript<>(RELEASE_FENCE_LUA, Long.class);
        this.reconcileScript = new DefaultRedisScript<>();
        this.reconcileScript.setResultType(List.class);
        this.reconcileScript.setScriptText(RECONCILE_LUA);
    }

    /** Reconciles one entity while normal toggles are fenced. */
    public ReconciliationResult reconcileEntity(String entityType, String entityId) {
        requireIdentity(entityType, entityId);
        RLock lock = redisson.getLock(CounterKeys.factMaintenanceLockKey(entityType, entityId));
        boolean locked = false;
        boolean fenced = false;
        String token = UUID.randomUUID().toString();
        try {
            locked = lock.tryLock(0L, TimeUnit.MILLISECONDS);
            if (!locked) {
                throw new IllegalStateException("Counter reconciliation lock is busy");
            }
            Long acquired = redis.execute(
                    acquireFenceScript,
                    List.of(CounterKeys.factMaintenanceFenceKey(entityType, entityId)),
                    token,
                    String.valueOf(FENCE_LEASE_MILLIS));
            fenced = Long.valueOf(1L).equals(acquired);
            if (!fenced) {
                throw new IllegalStateException("Counter reconciliation fence is busy");
            }

            long likeCount = countBitmapShards("like", entityType, entityId);
            long favCount = countBitmapShards("fav", entityType, entityId);
            List<?> raw = redis.execute(
                    reconcileScript,
                    List.of(
                            CounterKeys.sdsKey(entityType, entityId),
                            CounterKeys.aggKey(entityType, entityId),
                            CounterKeys.aggIndexKey(),
                            CounterKeys.factMaintenanceFenceKey(entityType, entityId),
                            CounterKeys.factEpochKey(entityType, entityId)),
                    token,
                    String.valueOf(CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE),
                    String.valueOf(CounterSchema.FIELD_SIZE),
                    String.valueOf(CounterSchema.IDX_LIKE),
                    String.valueOf(CounterSchema.IDX_FAV),
                    String.valueOf(likeCount),
                    String.valueOf(favCount));
            return mapResult(raw);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reconciling counters", exception);
        } finally {
            if (fenced) {
                redis.execute(
                        releaseFenceScript,
                        List.of(CounterKeys.factMaintenanceFenceKey(entityType, entityId)),
                        token);
            }
            if (locked) {
                lock.unlock();
            }
        }
    }

    private long countBitmapShards(String metric, String entityType, String entityId) {
        String pattern = String.format("bm:%s:%s:%s:*", metric, entityType, entityId);
        Set<String> keys = new LinkedHashSet<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (key != null) { keys.add(key); }
            }
        }
        if (keys.isEmpty()) { return 0L; }
        List<Object> values = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : keys) {
                connection.stringCommands().bitCount(key.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        long total = 0L;
        for (Object value : values) {
            if (!(value instanceof Number number) || number.longValue() < 0L) {
                throw new IllegalStateException("Bitmap BITCOUNT returned an invalid value");
            }
            total = Math.addExact(total, number.longValue());
            if (total > UINT32_MAX) {
                throw new IllegalStateException("Reaction count exceeds unsigned Int32");
            }
        }
        return total;
    }

    private static ReconciliationResult mapResult(List<?> raw) {
        if (raw == null || raw.size() != 3
                || !(raw.get(0) instanceof Number like)
                || !(raw.get(1) instanceof Number fav)
                || !(raw.get(2) instanceof Number epoch)
                || like.longValue() < 0L || fav.longValue() < 0L || epoch.longValue() < 0L) {
            throw new IllegalStateException("Counter reconciliation Lua returned an invalid result");
        }
        return new ReconciliationResult(like.longValue(), fav.longValue(), epoch.longValue());
    }

    private static void requireIdentity(String entityType, String entityId) {
        if (entityType == null || entityType.isBlank() || entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("Counter entity identity is required");
        }
    }

    public record ReconciliationResult(long likeCount, long favCount, long factEpoch) {}

    private static final String ACQUIRE_FENCE_LUA = """
            local key = KEYS[1]
            local token = ARGV[1]
            local lease = tonumber(ARGV[2])
            local reply = redis.call('TYPE', key)
            local kind = type(reply) == 'table' and reply['ok'] or reply
            if kind ~= 'none' and kind ~= 'string' then
              return redis.error_reply('counter fence has an invalid Redis type')
            end
            if not token or token == '' or not lease or lease <= 0 then
              return redis.error_reply('counter fence arguments are invalid')
            end
            if not redis.call('SET', key, token, 'NX', 'PX', lease) then return 0 end
            return 1
            """;

    private static final String RELEASE_FENCE_LUA = """
            local key = KEYS[1]
            if redis.call('GET', key) ~= ARGV[1] then return 0 end
            return redis.call('DEL', key)
            """;

    private static final String RECONCILE_LUA = """
            local cntKey = KEYS[1]
            local aggKey = KEYS[2]
            local aggIndexKey = KEYS[3]
            local fenceKey = KEYS[4]
            local epochKey = KEYS[5]
            local token = ARGV[1]
            local expectedLength = tonumber(ARGV[2])
            local fieldSize = tonumber(ARGV[3])
            local likeIndex = tonumber(ARGV[4])
            local favIndex = tonumber(ARGV[5])
            local likeCount = tonumber(ARGV[6])
            local favCount = tonumber(ARGV[7])
            local uint32Max = 4294967295

            local function keyType(key)
              local reply = redis.call('TYPE', key)
              return type(reply) == 'table' and reply['ok'] or reply
            end
            if (keyType(cntKey) ~= 'none' and keyType(cntKey) ~= 'string')
                  or (keyType(aggKey) ~= 'none' and keyType(aggKey) ~= 'hash')
                  or (keyType(aggIndexKey) ~= 'none' and keyType(aggIndexKey) ~= 'set')
                  or keyType(fenceKey) ~= 'string'
                  or (keyType(epochKey) ~= 'none' and keyType(epochKey) ~= 'string') then
              return redis.error_reply('counter reconciliation key has an invalid Redis type')
            end
            if redis.call('GET', fenceKey) ~= token then
              return redis.error_reply('counter reconciliation fence ownership lost')
            end
            if expectedLength ~= 20 or fieldSize ~= 4 or likeIndex ~= 1 or favIndex ~= 2
                  or not likeCount or not favCount or likeCount < 0 or favCount < 0
                  or likeCount > uint32Max or favCount > uint32Max
                  or likeCount ~= math.floor(likeCount) or favCount ~= math.floor(favCount) then
              return redis.error_reply('counter reconciliation arguments are invalid')
            end
            local raw = redis.call('GET', cntKey)
            if not raw or string.len(raw) ~= expectedLength then
              raw = string.rep(string.char(0), expectedLength)
            end
            local function encoded(value)
              return string.char(
                    math.floor(value / 16777216) % 256,
                    math.floor(value / 65536) % 256,
                    math.floor(value / 256) % 256,
                    value % 256)
            end
            local function replace(value, index, nextValue)
              local offset = index * fieldSize
              return string.sub(value, 1, offset) .. encoded(nextValue)
                    .. string.sub(value, offset + fieldSize + 1)
            end
            raw = replace(raw, likeIndex, likeCount)
            raw = replace(raw, favIndex, favCount)
            local epochText = redis.call('GET', epochKey) or '0'
            local epoch = tonumber(epochText)
            if not epoch or epoch < 0 or epoch ~= math.floor(epoch) then
              return redis.error_reply('counter fact epoch is invalid')
            end
            local nextEpoch = redis.call('INCR', epochKey)
            redis.call('SET', cntKey, raw)
            redis.call('HDEL', aggKey, tostring(likeIndex), tostring(favIndex))
            if redis.call('HLEN', aggKey) == 0 then
              redis.call('DEL', aggKey)
              redis.call('SREM', aggIndexKey, aggKey)
            end
            return {likeCount, favCount, nextEpoch}
            """;
}
