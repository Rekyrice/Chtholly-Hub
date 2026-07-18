package com.chtholly.counter.event;

import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计数事件聚合与刷写核心逻辑（Kafka / Spring Event 共用）。
 */
@Service
public class CounterAggregationProcessor {

    private static final Logger log = LoggerFactory.getLogger(CounterAggregationProcessor.class);
    private static final String EVENT_KEY_PREFIX = "counter:event:";
    private static final String EVENT_DEDUP_TTL_SECONDS = "604800";

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> aggIncrScript;
    private final DefaultRedisScript<Long> transferFieldScript;
    private final DefaultRedisScript<Long> cleanupEmptyAggScript;

    public CounterAggregationProcessor(StringRedisTemplate redis) {
        this.redis = redis;
        this.aggIncrScript = new DefaultRedisScript<>();
        this.aggIncrScript.setResultType(Long.class);
        this.aggIncrScript.setScriptText(AGG_INCR_LUA);

        this.transferFieldScript = new DefaultRedisScript<>();
        this.transferFieldScript.setResultType(Long.class);
        this.transferFieldScript.setScriptText(TRANSFER_FIELD_LUA);
        this.cleanupEmptyAggScript = new DefaultRedisScript<>();
        this.cleanupEmptyAggScript.setResultType(Long.class);
        this.cleanupEmptyAggScript.setScriptText(CLEANUP_EMPTY_AGG_LUA);
    }

    /** 将单条计数增量写入 Redis 聚合桶。 */
    public boolean applyEvent(CounterEvent evt) {
        if (evt.getEventId() == null || evt.getEventId().isBlank()) {
            throw new IllegalArgumentException("Counter event ID is required");
        }
        String aggKey = CounterKeys.aggKey(evt.getEntityType(), evt.getEntityId());
        String indexKey = CounterKeys.aggIndexKey();
        String eventKey = EVENT_KEY_PREFIX + evt.getEventId();
        String field = String.valueOf(evt.getIdx());
        boolean epochFenced = isPostReaction(evt);
        if (evt.getFactEpoch() < 0L) {
            throw new IllegalArgumentException("Counter event fact epoch must not be negative");
        }
        Long applied = redis.execute(
                aggIncrScript,
                List.of(aggKey, indexKey, eventKey,
                        CounterKeys.factEpochKey(evt.getEntityType(), evt.getEntityId())),
                field,
                String.valueOf(evt.getDelta()),
                EVENT_DEDUP_TTL_SECONDS,
                epochFenced ? "1" : "0",
                String.valueOf(evt.getFactEpoch()));
        return applied != null && applied == 1L;
    }

    private static boolean isPostReaction(CounterEvent evt) {
        if (!"post".equals(evt.getEntityType())) {
            return false;
        }
        if (evt.getIdx() == CounterSchema.IDX_LIKE && "like".equals(evt.getMetric())) {
            return true;
        }
        if (evt.getIdx() == CounterSchema.IDX_FAV && "fav".equals(evt.getMetric())) {
            return true;
        }
        if (evt.getIdx() == CounterSchema.IDX_LIKE || evt.getIdx() == CounterSchema.IDX_FAV
                || "like".equals(evt.getMetric()) || "fav".equals(evt.getMetric())) {
            throw new IllegalArgumentException("Reaction counter event metric and index do not match");
        }
        return false;
    }

    /** 将聚合增量刷写到 SDS 固定结构计数，固定延迟 1s。 */
    @Scheduled(fixedDelay = 1000L)
    public void flush() {
        String indexKey = CounterKeys.aggIndexKey();
        Set<String> keys = redis.opsForSet().members(indexKey);
        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (String aggKey : keys) {
            if (CounterKeys.aggIndexKey().equals(aggKey)) {
                continue;
            }
            flushAggKey(indexKey, aggKey);
        }
    }

    private void flushAggKey(String indexKey, String aggKey) {
        Map<Object, Object> entries = redis.opsForHash().entries(aggKey);
        if (entries.isEmpty()) {
            redis.execute(cleanupEmptyAggScript, List.of(aggKey, indexKey));
            return;
        }

        String[] parts = aggKey.split(":", 4);
        if (parts.length < 4) {
            log.warn("counter.agg flush skip malformed aggKey={}", aggKey);
            return;
        }

        String cntKey = CounterKeys.sdsKey(parts[2], parts[3]);

        for (Map.Entry<Object, Object> e : entries.entrySet()) {
            String field = String.valueOf(e.getKey());
            try {
                Integer.parseInt(field);
            } catch (NumberFormatException nfe) {
                continue;
            }

            try {
                redis.execute(transferFieldScript, List.of(cntKey, aggKey, indexKey),
                        String.valueOf(CounterSchema.SCHEMA_LEN),
                        String.valueOf(CounterSchema.FIELD_SIZE),
                        field);
            } catch (Exception ex) {
                log.warn("counter.agg flush failed aggKey={} field={}: {}",
                        aggKey, field, ex.getMessage(), ex);
            }
        }
    }

    private static final String AGG_INCR_LUA = """
            local aggKey = KEYS[1]
            local indexKey = KEYS[2]
            local eventKey = KEYS[3]
            local epochKey = KEYS[4]
            local field = ARGV[1]
            local delta = tonumber(ARGV[2])
            local dedupTtl = tonumber(ARGV[3])
            local epochFenced = ARGV[4] == '1'
            local expectedEpoch = tonumber(ARGV[5])
            if epochFenced then
              local epochTypeReply = redis.call('TYPE', epochKey)
              local epochType = type(epochTypeReply) == 'table' and epochTypeReply['ok'] or epochTypeReply
              if epochType ~= 'none' and epochType ~= 'string' then
                return redis.error_reply('counter fact epoch has an invalid Redis type')
              end
              local currentEpoch = tonumber(redis.call('GET', epochKey) or '0')
              if not currentEpoch or currentEpoch < 0 or currentEpoch ~= math.floor(currentEpoch)
                    or not expectedEpoch or expectedEpoch < 0
                    or expectedEpoch ~= math.floor(expectedEpoch) then
                return redis.error_reply('counter fact epoch is invalid')
              end
              if currentEpoch ~= expectedEpoch then return -1 end
            end
            if not redis.call('SET', eventKey, '1', 'NX', 'EX', dedupTtl) then
                return 0
            end
            redis.call('HINCRBY', aggKey, field, delta)
            redis.call('SADD', indexKey, aggKey)
            return 1
            """;

    private static final String TRANSFER_FIELD_LUA = """

            local cntKey = KEYS[1]
            local aggKey = KEYS[2]
            local indexKey = KEYS[3]
            local schemaLen = tonumber(ARGV[1])
            local fieldSize = tonumber(ARGV[2]) -- 固定为4
            local field = ARGV[3]
            local idx = tonumber(field)
            local rawDelta = redis.call('HGET', aggKey, field)
            if not rawDelta then
              if redis.call('HLEN', aggKey) == 0 then
                redis.call('DEL', aggKey)
                redis.call('SREM', indexKey, aggKey)
              end
              return 0
            end
            local delta = tonumber(rawDelta)

            local function read32be(s, off)
              local b = {string.byte(s, off+1, off+4)}
              local n = 0
              for i=1,4 do n = n * 256 + b[i] end
              return n
            end

            local function write32be(n)
              local t = {}
              for i=4,1,-1 do t[i] = n % 256; n = math.floor(n/256) end
              return string.char(unpack(t))
            end

            local cnt = redis.call('GET', cntKey)
            if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end
            local off = idx * fieldSize
            local v = read32be(cnt, off) + delta
            if v < 0 then v = 0 end
            local seg = write32be(v)
            cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off+fieldSize+1)
            redis.call('SET', cntKey, cnt)
            redis.call('HDEL', aggKey, field)
            if redis.call('HLEN', aggKey) == 0 then
              redis.call('DEL', aggKey)
              redis.call('SREM', indexKey, aggKey)
            end
            return delta
            """;

    private static final String CLEANUP_EMPTY_AGG_LUA = """
            local aggKey = KEYS[1]
            local indexKey = KEYS[2]
            if redis.call('HLEN', aggKey) == 0 then
                redis.call('DEL', aggKey)
                redis.call('SREM', indexKey, aggKey)
                return 1
            end
            return 0
            """;
}
