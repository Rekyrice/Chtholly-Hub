package com.chtholly.counter.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.service.CounterService;
import com.chtholly.counter.event.CounterEventPublisher;
import com.chtholly.counter.event.CounterEvent;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RBucket;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Distributed counter service: Redis Bitmap for idempotent like/fav facts,
 * Kafka/ApplicationEvent for async aggregation, SDS for O(1) count reads.
 *
 * <p>Rebuild path uses rate limiting and exponential backoff to avoid storms on hot entities.
 */
@Slf4j
@Service
public class CounterServiceImpl implements CounterService {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> toggleScript;
    private final DefaultRedisScript<Long> effectiveCountScript;
    private final CounterEventPublisher counterEventPublisher;
    private final RedissonClient redisson;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    @Value("${counter.rebuild.lock.ttl-ms:5000}")
    private long lockTtlMs;
    @Value("${counter.rebuild.rate.permits:3}")
    private int ratePermits;
    @Value("${counter.rebuild.rate.window-seconds:10}")
    private int rateWindowSeconds;
    @Value("${counter.rebuild.backoff.base-ms:500}")
    private long backoffBaseMs;
    @Value("${counter.rebuild.backoff.max-ms:30000}")
    private long backoffMaxMs;

    public CounterServiceImpl(StringRedisTemplate redis, CounterEventPublisher counterEventPublisher,
                              RedissonClient redisson,
                              PostMapper postMapper, UserMapper userMapper) {
        this.redis = redis;
        this.counterEventPublisher = counterEventPublisher;
        this.redisson = redisson;
        this.postMapper = postMapper;
        this.userMapper = userMapper;
        this.toggleScript = new DefaultRedisScript<>();
        this.toggleScript.setResultType(List.class);
        // 位图状态原子切换，仅在状态变化时返回 1
        this.toggleScript.setScriptText(TOGGLE_LUA);
        this.effectiveCountScript = new DefaultRedisScript<>();
        this.effectiveCountScript.setResultType(Long.class);
        this.effectiveCountScript.setScriptText(EFFECTIVE_COUNT_LUA);
    }

    /**
     * Likes an entity. Bitmap toggle is idempotent — returns true only on 0→1 transition.
     *
     * @return {@code true} if state changed (publishes delta event).
     */
    @Override
    public boolean like(String entityType, String entityId, long userId) {
        return toggle(entityType, entityId, userId, "like", CounterSchema.IDX_LIKE, true);
    }

    /** Removes a like; publishes delta=-1 event on 1→0 transition. */
    @Override
    public boolean unlike(String entityType, String entityId, long userId) {
        return toggle(entityType, entityId, userId, "like", CounterSchema.IDX_LIKE, false);
    }

    /** Favorites an entity (bitmap idempotent, same semantics as {@link #like}). */
    @Override
    public boolean fav(String entityType, String entityId, long userId) {
        return toggle(entityType, entityId, userId, "fav", CounterSchema.IDX_FAV, true);
    }

    /** Removes a favorite; publishes delta=-1 on state change. */
    @Override
    public boolean unfav(String entityType, String entityId, long userId) {
        return toggle(entityType, entityId, userId, "fav", CounterSchema.IDX_FAV, false);
    }

    /**
     * 位图状态切换：仅在状态变化时返回成功，并产出增量事件。
     * @param etype 实体类型
     * @param eid 实体 ID
     * @param uid 用户 ID
     * @param metric 指标名称（like/fav）
     * @param idx 指标索引（用于 SDS 固定结构定位）
     * @param add 是否置位（true=添加，false=移除）
     */
    private boolean toggle(String etype, String eid, long uid, String metric, int idx, boolean add) {
        // 固定分片定位：按用户ID映射到 chunk 与分片内 bit 偏移，避免单键膨胀与热点
        long chunk = BitmapShard.chunkOf(uid);
        // 分片内位偏移
        long bit = BitmapShard.bitOf(uid);
        String bmKey = CounterKeys.bitmapKey(metric, etype, eid, chunk);
        List<String> keys = List.of(
                bmKey,
                CounterKeys.factMaintenanceFenceKey(etype, eid),
                CounterKeys.factEpochKey(etype, eid));
        List<String> args = List.of(String.valueOf(bit), add ? "add" : "remove");
        List<?> rawResult = redis.execute(toggleScript, keys, args.toArray());
        ToggleResult result = mapToggleResult(rawResult);
        if (result.status() == -1L) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "互动计数正在维护，请稍后重试",
                    HttpStatus.SERVICE_UNAVAILABLE.value());
        }
        boolean ok = result.status() == 1L;
        if (ok) {
            int delta = add ? 1 : -1;
            CounterEvent event = enrichEvent(etype, eid, metric, idx, uid, delta);
            event.setFactEpoch(result.factEpoch());
            counterEventPublisher.publish(event);
        }
        return ok;
    }

    private static ToggleResult mapToggleResult(List<?> values) {
        if (values == null || values.size() != 2
                || !(values.get(0) instanceof Number status)
                || !(values.get(1) instanceof Number epoch)) {
            throw new IllegalStateException("Counter toggle Lua returned an invalid result");
        }
        long statusValue = status.longValue();
        long epochValue = epoch.longValue();
        if ((statusValue != -1L && statusValue != 0L && statusValue != 1L) || epochValue < 0L) {
            throw new IllegalStateException("Counter toggle Lua returned an invalid result");
        }
        return new ToggleResult(statusValue, epochValue);
    }

    /** 在事件源填充帖子/用户展示信息，避免下游监听器 N+1 查库。 */
    private CounterEvent enrichEvent(String etype, String eid, String metric, int idx, long uid, int delta) {
        CounterEvent event = CounterEvent.of(etype, eid, metric, idx, uid, delta);
        if (!"post".equals(etype)) {
            return event;
        }
        try {
            long postId = Long.parseLong(eid);
            Post post = postMapper.findById(postId);
            if (post != null && post.getCreatorId() != null) {
                event.setPostCreatorId(post.getCreatorId());
                event.setPostTitle(post.getTitle());
                event.setPostSlug(post.getSlug());
            }
            if ("like".equals(metric) && delta == 1) {
                User actor = userMapper.findById(uid);
                if (actor != null) {
                    event.setActorNickname(actor.getNickname());
                    event.setActorAvatar(actor.getAvatar());
                }
            }
        } catch (Exception e) {
            log.debug("CounterEvent 上下文填充失败 etype={} eid={}: {}", etype, eid, e.getMessage());
        }
        return event;
    }

    /**
     * Returns aggregated counts from SDS; triggers bitmap rebuild when structure is missing.
     *
     * @param metrics Subset of metrics to read (e.g. "like", "fav").
     */
    @Override
    public Map<String, Long> getCounts(String entityType, String entityId, List<String> metrics) {
        String sdsKey = CounterKeys.sdsKey(entityType, entityId);
        int expectedLen = CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE;
        // SDS 固定结构：按大端 32 位编码
        byte[] raw = getRaw(sdsKey);
        boolean needRebuild = (raw == null || raw.length != expectedLen);

        Map<String, Long> result = new LinkedHashMap<>();

        if (needRebuild) {
            List<String> bitmapMetrics = metrics.stream()
                    .filter(metric -> !"view".equals(metric))
                    .toList();
            if (metrics.contains("view")) {
                result.put("view", getEffectiveCount(entityType, entityId, "view"));
            }
            if (bitmapMetrics.isEmpty()) {
                return result;
            }
            log.info("计数结构不存在，需要重建");
            // 限流与指数退避：避免在热点实体上触发重建风暴
            if (inBackoff(entityType, entityId)) {
                for (String m : bitmapMetrics) {
                    result.putIfAbsent(m, 0L);
                }
                return result;
            }

            if (!allowedByRateLimiter(entityType, entityId)) {
                escalateBackoff(entityType, entityId);
                for (String m : bitmapMetrics) {
                    result.putIfAbsent(m, 0L);
                }
                return result;
            }

            String lockKey = "post".equals(entityType)
                    ? CounterKeys.factMaintenanceLockKey(entityType, entityId)
                    : String.format("lock:sds-rebuild:%s:%s", entityType, entityId);

            RLock lock = redisson.getLock(lockKey);
            boolean locked = false;

            try {
                // 使用 Redisson 看门狗机制：不指定租期，自动续约（由 Redisson 的 lockWatchdogTimeout 控制）
                locked = lock.tryLock(0L, TimeUnit.MILLISECONDS);
                if (!locked) {
                    escalateBackoff(entityType, entityId);
                    for (String m : bitmapMetrics) {
                        result.putIfAbsent(m, 0L);
                    }
                    return result;
                }
                // 依据位图分片统计真实计数（仅由持锁者执行重建）
                byte[] newSds = new byte[expectedLen];
                List<String> rebuildFields = new ArrayList<>();
                for (String m : bitmapMetrics) {
                    Integer idx = CounterSchema.NAME_TO_IDX.get(m);
                    if (idx == null) {
                        continue;
                    }
                    long sum = bitCountShardsPipelined(m, entityType, entityId);
                    writeInt32BE(newSds, idx * CounterSchema.FIELD_SIZE, sum);
                    result.put(m, sum);
                    rebuildFields.add(String.valueOf(idx));
                }
                // 回写SDS并清理聚合桶，避免重复加算
                setRaw(sdsKey, newSds);
                if (!rebuildFields.isEmpty()) {
                    String aggKey = CounterKeys.aggKey(entityType, entityId);
                    redis.opsForHash().delete(aggKey, rebuildFields.toArray());
                }
                resetBackoff(entityType, entityId);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                escalateBackoff(entityType, entityId);
                for (String m : bitmapMetrics) {
                    result.putIfAbsent(m, 0L);
                }
                return result;
            } finally {
                if (locked) {
                    try {
                        lock.unlock();
                    } catch (Exception ignore) {}
                }
            }
        } else {
            for (String m : metrics) {
                Integer idx = CounterSchema.NAME_TO_IDX.get(m);
                if (idx == null) {
                    continue;
                }

                int off = idx * CounterSchema.FIELD_SIZE;
                long val = readInt32BE(raw, off); // 大端读取单段 32 位值
                result.put(m, val);
            }
        }
        return result;
    }

    /**
     * Atomically reads SDS and the pending aggregation hash in one Redis script.
     *
     * <p>The flush path increments SDS before decrementing the aggregation hash. An atomic read
     * can therefore observe the old exact total, a temporary high estimate, or the new exact
     * total, but never the unsafe old-SDS/new-aggregation combination that would undercount.
     */
    @Override
    public long getEffectiveCount(String entityType, String entityId, String metric) {
        Integer index = CounterSchema.NAME_TO_IDX.get(metric);
        if (index == null) {
            throw new IllegalArgumentException("Unsupported counter metric: " + metric);
        }
        Long value = redis.execute(
                effectiveCountScript,
                List.of(CounterKeys.sdsKey(entityType, entityId), CounterKeys.aggKey(entityType, entityId)),
                String.valueOf(index),
                String.valueOf(CounterSchema.FIELD_SIZE),
                String.valueOf(CounterSchema.SCHEMA_LEN));
        return value == null ? 0L : Math.max(0L, value);
    }

    /**
     * 批量获取实体计数（管道批量 GET 降低 RTT）。
     * 缺失或结构异常（长度不符）时按零返回，保证接口稳定。
     * @param entityType 实体类型
     * @param entityIds 实体ID列表
     * @param metrics 指标名列表
     * @return 每个实体的指标计数映射
     */
    @Override
    public Map<String, Map<String, Long>> getCountsBatch(String entityType, List<String> entityIds, List<String> metrics) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        if (entityIds == null || entityIds.isEmpty() || metrics == null || metrics.isEmpty()) {
            return out;
        }

        List<String> keys = new ArrayList<>(entityIds.size());
        for (String eid : entityIds) {
            keys.add(CounterKeys.sdsKey(entityType, eid));
        }

        // 管道批量 GET：将多个 SDS 读取合并到一次往返
        List<Object> raws = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (String k : keys) {
                connection.stringCommands().get(k.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        int expectedLen = CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE;
        for (int i = 0; i < entityIds.size(); i++) {
            String eid = entityIds.get(i);
            Object rawObj = i < raws.size() ? raws.get(i) : null;
            byte[] raw = (rawObj instanceof byte[]) ? (byte[]) rawObj : null;

            Map<String, Long> m = new LinkedHashMap<>();
            if (raw != null && raw.length == expectedLen) {
                for (String name : metrics) {
                    Integer idx = CounterSchema.NAME_TO_IDX.get(name);
                    if (idx == null) continue;
                    int off = idx * CounterSchema.FIELD_SIZE;
                    long val = readInt32BE(raw, off);
                    m.put(name, val);
                }
            } else {
                for (String name : metrics) {
                    m.put(name, 0L); // 缺失或异常结构时补零，避免接口失败与重建风暴
                }
            }
            out.put(eid, m);
        }
        return out;
    }

    /**
     * 是否点赞判定：基于分片位图在分片内做位测试。
     * 毫秒级读取，不依赖计数快照。
     */
    @Override
    public boolean isLiked(String entityType, String entityId, long userId) {
        long chunk = BitmapShard.chunkOf(userId);
        long bit = BitmapShard.bitOf(userId);
        return getBit(CounterKeys.bitmapKey("like", entityType, entityId, chunk), bit);
    }

    /**
     * 是否收藏判定：同点赞，基于分片位图位测试。
     */
    @Override
    public boolean isFaved(String entityType, String entityId, long userId) {
        long chunk = BitmapShard.chunkOf(userId);
        long bit = BitmapShard.bitOf(userId);
        return getBit(CounterKeys.bitmapKey("fav", entityType, entityId, chunk), bit);
    }

    @Override
    public Map<Long, Boolean> batchIsLiked(long userId, List<Long> postIds) {
        return batchRelationBits("like", userId, postIds);
    }

    @Override
    public Map<Long, Boolean> batchIsFaved(long userId, List<Long> postIds) {
        return batchRelationBits("fav", userId, postIds);
    }

    private Map<Long, Boolean> batchRelationBits(String metric, long userId, List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        long chunk = BitmapShard.chunkOf(userId);
        long bit = BitmapShard.bitOf(userId);
        List<Long> ids = postIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }

        List<Object> pipelineResults = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (Long postId : ids) {
                String key = CounterKeys.bitmapKey(metric, "post", String.valueOf(postId), chunk);
                connection.stringCommands().getBit(key.getBytes(StandardCharsets.UTF_8), bit);
            }
            return null;
        });

        Map<Long, Boolean> out = new LinkedHashMap<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            Object raw = i < pipelineResults.size() ? pipelineResults.get(i) : null;
            out.put(ids.get(i), Boolean.TRUE.equals(raw));
        }
        return out;
    }

    /**
     * 读取位图某偏移位（GETBIT）。
     * @param key 位图分片键
     * @param offset 分片内位偏移
     * @return 位是否为 1
     */
    private boolean getBit(String key, long offset) {
        Boolean bit = redis.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().getBit(key.getBytes(StandardCharsets.UTF_8), offset));
        return Boolean.TRUE.equals(bit);
    }

    /**
     * 读取 SDS 原始字节（固定结构，长度=字段数×4）。
     */
    private byte[] getRaw(String key) {
        return redis.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 写入 SDS 原始字节（覆盖式写）。
     */
    private void setRaw(String key, byte[] val) {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.stringCommands().set(key.getBytes(StandardCharsets.UTF_8), val);
            return null;
        });
    }

    /**
     * 是否处于指数退避期：期间跳过重建并返回降级结果。
     */
    private boolean inBackoff(String entityType, String entityId) {
        String bKey = String.format("backoff:sds-rebuild:until:%s:%s", entityType, entityId);
        RBucket<Long> bucket = redisson.getBucket(bKey);
        Long until = bucket.get();

        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * 增加退避级别并设置下次允许尝试的时间（指数递增，封顶）。
     */
    private void escalateBackoff(String entityType, String entityId) {
        String eKey = String.format("backoff:sds-rebuild:exp:%s:%s", entityType, entityId);
        String uKey = String.format("backoff:sds-rebuild:until:%s:%s", entityType, entityId);

        RBucket<Integer> expB = redisson.getBucket(eKey);
        RBucket<Long> untilB = redisson.getBucket(uKey);
        Integer exp = expB.get();

        int nextExp = Math.min(exp == null ? 0 : exp + 1, 10);
        long delay = Math.min(backoffBaseMs * (1L << nextExp), backoffMaxMs);
        long until = System.currentTimeMillis() + delay;

        // 设置过期时间，避免长时间残留
        expB.set(nextExp);
        untilB.set(until, Duration.ofMillis(delay + 1000));
    }

    /**
     * 重置退避状态（成功重建后）。
     */
    private void resetBackoff(String entityType, String entityId) {
        String eKey = String.format("backoff:sds-rebuild:exp:%s:%s", entityType, entityId);
        String uKey = String.format("backoff:sds-rebuild:until:%s:%s", entityType, entityId);

        try {
            redisson.getBucket(eKey).delete();
        } catch (Exception ignore) {}

        try {
            redisson.getBucket(uKey).delete();
        } catch (Exception ignore) {}
    }

    /**
     * 限流判断：单位窗口可重建次数，防止抖动与风暴。
     */
    private boolean allowedByRateLimiter(String entityType, String entityId) {
        String rlKey = String.format("rl:sds-rebuild:%s:%s", entityType, entityId);
        RRateLimiter limiter = redisson.getRateLimiter(rlKey);

        // 初始化速率（如已存在则忽略）
        limiter.trySetRate(RateType.OVERALL, ratePermits, Duration.ofSeconds(rateWindowSeconds));

        return limiter.tryAcquire(1);
    }

    /**
     * 以大端序读取 32 位无符号整型。
     */
    private static long readInt32BE(byte[] buf, int off) {
        long n = 0;
        for (int i = 0; i < 4; i++) {
            n = (n << 8) | (buf[off + i] & 0xFFL);
        }
        return n;
    }

    /**
     * 以大端序写入 32 位无符号整型（截断到 0~2^32-1）。
     */
    private static void writeInt32BE(byte[] buf, int off, long val) {
        long n = Math.max(0, Math.min(val, 0xFFFF_FFFFL));
        buf[off] = (byte) ((n >>> 24) & 0xFF);
        buf[off + 1] = (byte) ((n >>> 16) & 0xFF);
        buf[off + 2] = (byte) ((n >>> 8) & 0xFF);
        buf[off + 3] = (byte) (n & 0xFF);
    }

    /**
     * 基于位图分片进行管道化 BITCOUNT 汇总，用于按事实重建计数。
     * 使用 SCAN 迭代分片 key，避免 KEYS 阻塞 Redis。
     */
    private long bitCountShardsPipelined(String metric, String etype, String eid) {
        String pattern = String.format("bm:%s:%s:%s:*", metric, etype, eid);
        Set<String> keys = new LinkedHashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        if (keys.isEmpty()) {
            return 0L;
        }

        // 管道批量 BITCOUNT 汇总
        List<Object> res = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (String k : keys) {
                connection.stringCommands().bitCount(k.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        long sum = 0L;

        for (Object o : res) {
            if (o instanceof Number n) {
                sum += n.longValue();
            }
        }
        return sum;
    }

    private record ToggleResult(long status, long factEpoch) {}

    // Redis 内嵌 Lua（Redis 5/6 的 Lua 5.1），位图原子切换（分片内偏移）
    private static final String TOGGLE_LUA = """
            local bmKey = KEYS[1]
            local fenceKey = KEYS[2]
            local epochKey = KEYS[3]
            local offset = tonumber(ARGV[1])
            local op = ARGV[2] -- 'add' or 'remove'
            local function keyType(key)
              local reply = redis.call('TYPE', key)
              if type(reply) == 'table' then return reply['ok'] end
              return reply
            end
            local bitmapType = keyType(bmKey)
            local fenceType = keyType(fenceKey)
            local epochType = keyType(epochKey)
            if (bitmapType ~= 'none' and bitmapType ~= 'string')
                  or (fenceType ~= 'none' and fenceType ~= 'string')
                  or (epochType ~= 'none' and epochType ~= 'string') then
              return redis.error_reply('counter fact key has an invalid Redis type')
            end
            local epochText = redis.call('GET', epochKey) or '0'
            local epoch = tonumber(epochText)
            if not epoch or epoch < 0 or epoch ~= math.floor(epoch) then
              return redis.error_reply('counter fact epoch is invalid')
            end
            if redis.call('EXISTS', fenceKey) == 1 then
              return {-1, epoch}
            end
            local prev = redis.call('GETBIT', bmKey, offset)
            if op == 'add' then
              if prev == 1 then return {0, epoch} end
              redis.call('SETBIT', bmKey, offset, 1)
              return {1, epoch}
            elseif op == 'remove' then
              if prev == 0 then return {0, epoch} end
              redis.call('SETBIT', bmKey, offset, 0)
              return {1, epoch}
            end
            return redis.error_reply('counter toggle operation is invalid')
            """;

    private static final String EFFECTIVE_COUNT_LUA = """
            local cntKey = KEYS[1]
            local aggKey = KEYS[2]
            local idx = tonumber(ARGV[1])
            local fieldSize = tonumber(ARGV[2])
            local schemaLen = tonumber(ARGV[3])

            local function read32be(value, offset)
              local b = {string.byte(value, offset + 1, offset + 4)}
              local result = 0
              for i = 1, 4 do result = result * 256 + b[i] end
              return result
            end

            local persisted = 0
            local raw = redis.call('GET', cntKey)
            if raw and string.len(raw) == schemaLen * fieldSize then
              persisted = read32be(raw, idx * fieldSize)
            end
            local pending = tonumber(redis.call('HGET', aggKey, tostring(idx)) or '0')
            local effective = persisted + pending
            if effective < 0 then return 0 end
            return effective
            """;
}
