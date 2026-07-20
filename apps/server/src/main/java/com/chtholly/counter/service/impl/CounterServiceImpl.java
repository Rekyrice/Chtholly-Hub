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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Distributed counter service: Redis Bitmap for idempotent like/fav facts,
 * Kafka/ApplicationEvent for async aggregation, SDS for O(1) count reads.
 */
@Slf4j
@Service
public class CounterServiceImpl implements CounterService {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> toggleScript;
    private final DefaultRedisScript<Long> effectiveCountScript;
    private final CounterEventPublisher counterEventPublisher;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final CounterCalibrationService calibrationService;

    public CounterServiceImpl(StringRedisTemplate redis, CounterEventPublisher counterEventPublisher,
                              PostMapper postMapper, UserMapper userMapper,
                              CounterCalibrationService calibrationService) {
        this.redis = redis;
        this.counterEventPublisher = counterEventPublisher;
        this.postMapper = postMapper;
        this.userMapper = userMapper;
        this.calibrationService = calibrationService;
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
        CounterSchema.requirePersistableIdentity(etype, eid);
        // 固定分片定位：按用户ID映射到 chunk 与分片内 bit 偏移，避免单键膨胀与热点
        long chunk = BitmapShard.chunkOf(uid);
        // 分片内位偏移
        long bit = BitmapShard.bitOf(uid);
        String bmKey = CounterKeys.bitmapKey(metric, etype, eid, chunk);
        List<String> keys = List.of(
                bmKey,
                CounterKeys.sdsKey(etype, eid),
                CounterKeys.factMaintenanceFenceKey(etype, eid),
                CounterKeys.factEpochKey(etype, eid),
                CounterKeys.bitmapShardIndexKey(metric, etype, eid),
                CounterKeys.bitmapShardIndexKey(
                        "like".equals(metric) ? "fav" : "like", etype, eid),
                CounterKeys.bitmapCalibrationCandidatesKey(),
                CounterKeys.bitmapShardIndexCountKey(metric, etype, eid),
                CounterKeys.bitmapShardIndexCountKey(
                        "like".equals(metric) ? "fav" : "like", etype, eid));
        List<String> args = List.of(
                String.valueOf(bit),
                add ? "add" : "remove",
                String.valueOf(idx),
                String.valueOf(CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE),
                String.valueOf(CounterSchema.FIELD_SIZE),
                CounterBitmapIndexService.SHARD_INDEX_SENTINEL,
                etype + ":" + eid);
        List<?> rawResult = redis.execute(toggleScript, keys, args.toArray());
        ToggleResult result = mapToggleResult(rawResult);
        if (result.status() == 2L) {
            calibrationService.reconcileEntity(etype, eid);
            result = mapToggleResult(redis.execute(toggleScript, keys, args.toArray()));
            if (result.status() == 2L) {
                throw new IllegalStateException("Counter reconciliation did not restore the SDS structure");
            }
        }
        if (result.status() == -1L) {
            try {
                calibrationService.reconcileEntity(etype, eid);
                result = mapToggleResult(redis.execute(toggleScript, keys, args.toArray()));
            } catch (IllegalStateException exception) {
                throw maintenanceUnavailable(exception);
            }
        }
        if (result.status() == -1L || result.status() == 3L) {
            throw maintenanceUnavailable(null);
        }
        if (result.status() == 2L) {
            throw new IllegalStateException("Counter reconciliation did not restore the SDS structure");
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

    private static BusinessException maintenanceUnavailable(Throwable cause) {
        BusinessException exception = new BusinessException(
                ErrorCode.CONFLICT,
                "互动计数正在维护，请稍后重试",
                HttpStatus.SERVICE_UNAVAILABLE.value());
        if (cause != null) { exception.initCause(cause); }
        return exception;
    }

    private static ToggleResult mapToggleResult(List<?> values) {
        if (values == null || values.size() != 2
                || !(values.get(0) instanceof Number status)
                || !(values.get(1) instanceof Number epoch)) {
            throw new IllegalStateException("Counter toggle Lua returned an invalid result");
        }
        long statusValue = status.longValue();
        long epochValue = epoch.longValue();
        if ((statusValue != -1L && statusValue != 0L && statusValue != 1L
                && statusValue != 2L && statusValue != 3L)
                || epochValue < 0L) {
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
     * Returns aggregated counts from SDS and reconciles reaction fields from Bitmap authority when missing.
     *
     * @param metrics Subset of metrics to read (e.g. "like", "fav").
     */
    @Override
    public Map<String, Long> getCounts(String entityType, String entityId, List<String> metrics) {
        CounterSchema.requirePersistableIdentity(entityType, entityId);
        String sdsKey = CounterKeys.sdsKey(entityType, entityId);
        int expectedLen = CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE;
        byte[] raw = getRaw(sdsKey);
        Map<String, Long> result = new LinkedHashMap<>();

        if (raw == null || raw.length != expectedLen) {
            if (metrics.contains("view")) {
                result.put("view", getEffectiveCount(entityType, entityId, "view"));
            }
            boolean needsReaction = metrics.stream()
                    .anyMatch(metric -> "like".equals(metric) || "fav".equals(metric));
            if (!needsReaction) {
                return result;
            }
            try {
                calibrationService.reconcileEntity(entityType, entityId);
            } catch (RuntimeException exception) {
                log.warn("Counter read reconciliation failed entityType={} entityId={}: {}",
                        entityType, entityId, exception.getMessage());
            }
            raw = getRaw(sdsKey);
            if (raw == null || raw.length != expectedLen) {
                throw new BusinessException(
                        ErrorCode.INTERNAL_ERROR,
                        "互动计数暂时不可用，请稍后重试",
                        HttpStatus.SERVICE_UNAVAILABLE.value());
            }
        }

        for (String metric : metrics) {
            if (result.containsKey(metric)) { continue; }
            Integer index = CounterSchema.NAME_TO_IDX.get(metric);
            if (index != null) {
                result.put(metric, readInt32BE(raw, index * CounterSchema.FIELD_SIZE));
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
     * reaction SDS 缺失或结构异常时从 Bitmap 权威事实校准；恢复失败则返回 503。
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
                m.putAll(getCounts(entityType, eid, metrics));
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
     * 以大端序读取 32 位无符号整型。
     */
    private static long readInt32BE(byte[] buf, int off) {
        long n = 0;
        for (int i = 0; i < 4; i++) {
            n = (n << 8) | (buf[off + i] & 0xFFL);
        }
        return n;
    }

    private record ToggleResult(long status, long factEpoch) {}

    // Redis 内嵌 Lua（Redis 5/6 的 Lua 5.1），位图原子切换（分片内偏移）
    private static final String TOGGLE_LUA = """
            local bmKey = KEYS[1]
            local cntKey = KEYS[2]
            local fenceKey = KEYS[3]
            local epochKey = KEYS[4]
            local bitmapIndexKey = KEYS[5]
            local peerBitmapIndexKey = KEYS[6]
            local candidatesKey = KEYS[7]
            local bitmapIndexCountKey = KEYS[8]
            local peerBitmapIndexCountKey = KEYS[9]
            local offset = tonumber(ARGV[1])
            local op = ARGV[2] -- 'add' or 'remove'
            local idx = tonumber(ARGV[3])
            local expectedLength = tonumber(ARGV[4])
            local fieldSize = tonumber(ARGV[5])
            local indexSentinel = ARGV[6]
            local candidateMember = ARGV[7]
            local uint32Max = 4294967295
            local function keyType(key)
              local reply = redis.call('TYPE', key)
              if type(reply) == 'table' then return reply['ok'] end
              return reply
            end
            local bitmapType = keyType(bmKey)
            local counterType = keyType(cntKey)
            local fenceType = keyType(fenceKey)
            local epochType = keyType(epochKey)
            local bitmapIndexType = keyType(bitmapIndexKey)
            local peerBitmapIndexType = keyType(peerBitmapIndexKey)
            local candidatesType = keyType(candidatesKey)
            local bitmapIndexCountType = keyType(bitmapIndexCountKey)
            local peerBitmapIndexCountType = keyType(peerBitmapIndexCountKey)
            if (bitmapType ~= 'none' and bitmapType ~= 'string')
                  or (counterType ~= 'none' and counterType ~= 'string')
                  or (fenceType ~= 'none' and fenceType ~= 'string')
                  or (epochType ~= 'none' and epochType ~= 'string')
                  or (bitmapIndexType ~= 'none' and bitmapIndexType ~= 'set')
                  or (peerBitmapIndexType ~= 'none' and peerBitmapIndexType ~= 'set')
                  or (candidatesType ~= 'none' and candidatesType ~= 'zset')
                  or (bitmapIndexCountType ~= 'none' and bitmapIndexCountType ~= 'string')
                  or (peerBitmapIndexCountType ~= 'none' and peerBitmapIndexCountType ~= 'string') then
              return redis.error_reply('counter fact key has an invalid Redis type')
            end
            if not offset or offset < 0 or offset >= 32768 or offset ~= math.floor(offset)
                  or (op ~= 'add' and op ~= 'remove')
                  or (idx ~= 1 and idx ~= 2)
                  or expectedLength ~= 20 or fieldSize ~= 4
                  or indexSentinel ~= '@v1' or not candidateMember or candidateMember == '' then
              return redis.error_reply('counter toggle arguments are invalid')
            end
            local epochText = redis.call('GET', epochKey) or '0'
            local epoch = tonumber(epochText)
            if not epoch or epoch < 0 or epoch ~= math.floor(epoch) then
              return redis.error_reply('counter fact epoch is invalid')
            end
            if redis.call('EXISTS', fenceKey) == 1 then
              return {-1, epoch}
            end
            local raw = redis.call('GET', cntKey)
            if not raw or string.len(raw) ~= expectedLength then
              return {2, epoch}
            end
            local knownCandidate = redis.call('ZSCORE', candidatesKey, candidateMember)
            local hasMetadata = knownCandidate or bitmapType ~= 'none'
                  or bitmapIndexType ~= 'none' or peerBitmapIndexType ~= 'none'
                  or bitmapIndexCountType ~= 'none' or peerBitmapIndexCountType ~= 'none'
            local function indexIsComplete(indexKey, countKey)
              if redis.call('SISMEMBER', indexKey, indexSentinel) == 0 then return false end
              local expectedText = redis.call('GET', countKey)
              if not expectedText or not string.match(expectedText, '^%d+$')
                    or (expectedText ~= '0' and string.match(expectedText, '^0')) then
                return false
              end
              local expected = tonumber(expectedText)
              return expected and expected >= 0 and expected == math.floor(expected)
                    and redis.call('SCARD', indexKey) - 1 == expected
            end
            if hasMetadata and (not indexIsComplete(bitmapIndexKey, bitmapIndexCountKey)
                  or not indexIsComplete(peerBitmapIndexKey, peerBitmapIndexCountKey)) then
                return {3, epoch}
            end
            redis.call('SADD', bitmapIndexKey, indexSentinel)
            redis.call('SADD', peerBitmapIndexKey, indexSentinel)
            redis.call('SETNX', bitmapIndexCountKey, '0')
            redis.call('SETNX', peerBitmapIndexCountKey, '0')
            redis.call('ZADD', candidatesKey, 'NX', 0, candidateMember)
            local prev = redis.call('GETBIT', bmKey, offset)
            local target = op == 'add' and 1 or 0
            if prev == target then
              if redis.call('BITCOUNT', bmKey) == 0 then
                redis.call('DEL', bmKey)
                redis.call('SREM', bitmapIndexKey, bmKey)
              else
                redis.call('SADD', bitmapIndexKey, bmKey)
              end
              redis.call('SET', bitmapIndexCountKey,
                    tostring(redis.call('SCARD', bitmapIndexKey) - 1))
              return {0, epoch}
            end
            local function read32be(value, byteOffset)
              local b1, b2, b3, b4 = string.byte(value, byteOffset + 1, byteOffset + 4)
              return ((b1 * 256 + b2) * 256 + b3) * 256 + b4
            end
            local function encoded(value)
              return string.char(
                    math.floor(value / 16777216) % 256,
                    math.floor(value / 65536) % 256,
                    math.floor(value / 256) % 256,
                    value % 256)
            end
            local byteOffset = idx * fieldSize
            local nextCount = read32be(raw, byteOffset) + (target - prev)
            if nextCount < 0 or nextCount > uint32Max then
              return redis.error_reply('counter toggle would overflow unsigned Int32')
            end
            local nextRaw = string.sub(raw, 1, byteOffset) .. encoded(nextCount)
                  .. string.sub(raw, byteOffset + fieldSize + 1)
            redis.call('SETBIT', bmKey, offset, target)
            redis.call('SET', cntKey, nextRaw)
            if redis.call('BITCOUNT', bmKey) == 0 then
              redis.call('DEL', bmKey)
              redis.call('SREM', bitmapIndexKey, bmKey)
            else
              redis.call('SADD', bitmapIndexKey, bmKey)
            end
            redis.call('SET', bitmapIndexCountKey,
                  tostring(redis.call('SCARD', bitmapIndexKey) - 1))
            return {1, epoch}
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
