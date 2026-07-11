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
    private final DefaultRedisScript<Long> incrScript;
    private final DefaultRedisScript<Long> decrScript;

    public CounterAggregationProcessor(StringRedisTemplate redis) {
        this.redis = redis;
        this.aggIncrScript = new DefaultRedisScript<>();
        this.aggIncrScript.setResultType(Long.class);
        this.aggIncrScript.setScriptText(AGG_INCR_LUA);

        this.incrScript = new DefaultRedisScript<>();
        this.incrScript.setResultType(Long.class);
        this.incrScript.setScriptText(INCR_FIELD_LUA);

        this.decrScript = new DefaultRedisScript<>();
        this.decrScript.setResultType(Long.class);
        this.decrScript.setScriptText(DECR_FIELD_LUA);
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
        Long applied = redis.execute(
                aggIncrScript,
                List.of(aggKey, indexKey, eventKey),
                field,
                String.valueOf(evt.getDelta()),
                EVENT_DEDUP_TTL_SECONDS);
        return applied != null && applied == 1L;
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
            redis.opsForSet().remove(indexKey, aggKey);
            redis.delete(aggKey);
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
            long delta;
            try {
                delta = Long.parseLong(String.valueOf(e.getValue()));
            } catch (NumberFormatException nfe) {
                continue;
            }
            if (delta == 0) {
                continue;
            }
            int idx;
            try {
                idx = Integer.parseInt(field);
            } catch (NumberFormatException nfe) {
                continue;
            }

            try {
                redis.execute(incrScript, List.of(cntKey),
                        String.valueOf(CounterSchema.SCHEMA_LEN),
                        String.valueOf(CounterSchema.FIELD_SIZE),
                        String.valueOf(idx),
                        String.valueOf(delta));
                redis.execute(decrScript, List.of(aggKey), field, String.valueOf(delta));
            } catch (Exception ex) {
                log.warn("counter.agg flush failed aggKey={} field={} delta={}: {}",
                        aggKey, field, delta, ex.getMessage(), ex);
            }
        }

        Long size = redis.opsForHash().size(aggKey);
        if (size != null && size == 0L) {
            redis.delete(aggKey);
            redis.opsForSet().remove(indexKey, aggKey);
        }
    }

    private static final String AGG_INCR_LUA = """
            local aggKey = KEYS[1]
            local indexKey = KEYS[2]
            local eventKey = KEYS[3]
            local field = ARGV[1]
            local delta = tonumber(ARGV[2])
            local dedupTtl = tonumber(ARGV[3])
            if not redis.call('SET', eventKey, '1', 'NX', 'EX', dedupTtl) then
                return 0
            end
            redis.call('HINCRBY', aggKey, field, delta)
            redis.call('SADD', indexKey, aggKey)
            return 1
            """;

    private static final String INCR_FIELD_LUA = """

            local cntKey = KEYS[1]
            local schemaLen = tonumber(ARGV[1])
            local fieldSize = tonumber(ARGV[2]) -- 固定为4
            local idx = tonumber(ARGV[3])
            local delta = tonumber(ARGV[4])

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
            return 1
            """;

    private static final String DECR_FIELD_LUA = """
            local key = KEYS[1]
            local field = ARGV[1]
            local delta = tonumber(ARGV[2])
            local v = redis.call('HINCRBY', key, field, -delta)
            if v == 0 then
                redis.call('HDEL', key, field)
            end
            return v
            """;
}
