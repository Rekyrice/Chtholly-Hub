package com.chtholly.counter.event;

import com.chtholly.common.kafka.AbstractKafkaConsumer;
import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 灾难场景下的非成员计数重建消费者：基于 earliest 回放历史事件，直接折叠到 SDS。
 * 点赞/收藏由 MySQL 关系事实执行绝对校准，旧增量事件不能替代终态关系，因此会被明确跳过。
 * 默认关闭，仅当 counter.rebuild.enabled=true 时启用。
 */
@Service
@ConditionalOnExpression("${counter.rebuild.enabled:false} == true && ${kafka.enabled:false} == true")
public class CounterRebuildConsumer extends AbstractKafkaConsumer {

    private static final String CONSUMER_GROUP = "counter-rebuild";
    private static final Logger log = LoggerFactory.getLogger(CounterRebuildConsumer.class);

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> incrScript;
    private final DefaultRedisScript<Long> dedupeIncrScript;
    private final AtomicBoolean reactionSkipWarningLogged = new AtomicBoolean();

    public CounterRebuildConsumer(ObjectMapper objectMapper,
                                  StringRedisTemplate redis,
                                  KafkaTemplate<String, String> kafka,
                                  DeadLetterMessageService deadLetterMessageService) {
        super(kafka, objectMapper, deadLetterMessageService);
        this.redis = redis;
        this.incrScript = new DefaultRedisScript<>();
        this.incrScript.setResultType(Long.class);
        this.incrScript.setScriptText(INCR_FIELD_LUA);
        this.dedupeIncrScript = new DefaultRedisScript<>();
        this.dedupeIncrScript.setResultType(Long.class);
        this.dedupeIncrScript.setScriptText(DEDUPE_INCR_FIELD_LUA);
    }

    @KafkaListener(
            topics = CounterTopics.EVENTS,
            groupId = CONSUMER_GROUP,
            properties = {"auto.offset.reset=earliest"}
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consumeRecord(record, ack);
    }

    @KafkaListener(topics = CounterTopics.EVENTS + "-retry", groupId = CONSUMER_GROUP + "-retry")
    public void onRetryMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consumeRetryRecord(record, ack);
    }

    @Override
    protected void process(String sourceTopic, String messageKey, String payload, int retryCount) throws Exception {
        CounterEvent evt = objectMapper.readValue(payload, CounterEvent.class);
        applyRebuildEvent(evt);
    }

    boolean applyRebuildEvent(CounterEvent evt) {
        if (isReaction(evt)) {
            if (reactionSkipWarningLogged.compareAndSet(false, true)) {
                log.warn("Kafka counter rebuild skips legacy like/fav deltas; rebuild reaction "
                        + "Bitmap, SDS, and snapshots from authoritative MySQL relations");
            }
            return false;
        }
        String cntKey = CounterKeys.sdsKey(evt.getEntityType(), evt.getEntityId());
        String completeKey = CounterKeys.reactionProjectionCompleteKey(
                evt.getEntityType(), evt.getEntityId());
        String fenceKey = CounterKeys.factMaintenanceFenceKey(
                evt.getEntityType(), evt.getEntityId());
        if (evt.getFactEpoch() < 0L) {
            throw new IllegalArgumentException("Counter event fact epoch must not be negative");
        }
        List<String> keys;
        DefaultRedisScript<Long> script;
        if (evt.getEventId() == null || evt.getEventId().isBlank()) {
            keys = List.of(cntKey, completeKey, fenceKey);
            script = incrScript;
        } else {
            keys = List.of(
                    cntKey,
                    CounterKeys.eventDedupeKey(evt.getEventId()),
                    completeKey,
                    fenceKey);
            script = dedupeIncrScript;
        }
        Long applied = redis.execute(script, keys,
            String.valueOf(CounterSchema.SCHEMA_LEN),
            String.valueOf(CounterSchema.FIELD_SIZE),
            String.valueOf(evt.getIdx()),
            String.valueOf(evt.getDelta()));
        return Long.valueOf(1L).equals(applied);
    }

    private static boolean isReaction(CounterEvent evt) {
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

    @Override
    protected String consumerName() {
        return CONSUMER_GROUP;
    }

    private static final String INCR_FIELD_LUA = """

            local cntKey = KEYS[1]
            local completeKey = KEYS[2]
            local fenceKey = KEYS[3]
            local schemaLen = tonumber(ARGV[1])
            local fieldSize = tonumber(ARGV[2]) -- 固定为4
            local idx = tonumber(ARGV[3])
            local delta = tonumber(ARGV[4])
            local uint32Max = 4294967295
            if schemaLen ~= 5 or fieldSize ~= 4
                  or not idx or idx < 0 or idx >= schemaLen
                  or idx ~= math.floor(idx)
                  or not delta or delta ~= math.floor(delta) then
              return redis.error_reply('counter rebuild arguments are invalid')
            end

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

            local typeReply = redis.call('TYPE', cntKey)
            local cntType = type(typeReply) == 'table' and typeReply['ok'] or typeReply
            local cnt = nil
            if cntType == 'string' then cnt = redis.call('GET', cntKey) end
            if not cnt or string.len(cnt) ~= schemaLen * fieldSize then
              local fenceTypeReply = redis.call('TYPE', fenceKey)
              local fenceType =
                    type(fenceTypeReply) == 'table' and fenceTypeReply['ok'] or fenceTypeReply
              if fenceType ~= 'none' and fenceType ~= 'string' then
                redis.call('DEL', completeKey)
                return redis.error_reply('counter reaction maintenance fence has an invalid Redis type')
              end
              if fenceType == 'string' then
                local fenceValue = redis.call('GET', fenceKey)
                if string.sub(fenceValue, 1, 7) ~= '@dirty:' then
                  if string.sub(fenceValue, 1, 10) == '@prepared:' then
                    redis.call('SET', fenceKey, '@dirty:' .. string.sub(fenceValue, 11))
                  else
                    redis.call('SET', fenceKey, '@dirty:' .. fenceValue)
                  end
                end
              end
              redis.call('DEL', completeKey)
              if cntType ~= 'none' and cntType ~= 'string' then
                redis.call('DEL', cntKey)
              end
              cnt = string.rep(string.char(0), schemaLen * fieldSize)
            end
            local off = idx * fieldSize
            local v = read32be(cnt, off) + delta
            if v < 0 then v = 0 end
            if v > uint32Max then
              return redis.error_reply('counter rebuild would overflow unsigned Int32')
            end
            local seg = write32be(v)
            cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off+fieldSize+1)
            redis.call('SET', cntKey, cnt)
            return 1
            """;

    private static final String DEDUPE_INCR_FIELD_LUA = """
            local cntKey = KEYS[1]
            local dedupeKey = KEYS[2]
            local completeKey = KEYS[3]
            local fenceKey = KEYS[4]
            local schemaLen = tonumber(ARGV[1])
            local fieldSize = tonumber(ARGV[2])
            local idx = tonumber(ARGV[3])
            local delta = tonumber(ARGV[4])
            local uint32Max = 4294967295
            if schemaLen ~= 5 or fieldSize ~= 4
                  or not idx or idx < 0 or idx >= schemaLen
                  or idx ~= math.floor(idx)
                  or not delta or delta ~= math.floor(delta) then
              return redis.error_reply('counter rebuild arguments are invalid')
            end
            if redis.call('EXISTS', dedupeKey) ~= 0 then return 0 end

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

            local typeReply = redis.call('TYPE', cntKey)
            local cntType = type(typeReply) == 'table' and typeReply['ok'] or typeReply
            local cnt = nil
            if cntType == 'string' then cnt = redis.call('GET', cntKey) end
            if not cnt or string.len(cnt) ~= schemaLen * fieldSize then
              local fenceTypeReply = redis.call('TYPE', fenceKey)
              local fenceType =
                    type(fenceTypeReply) == 'table' and fenceTypeReply['ok'] or fenceTypeReply
              if fenceType ~= 'none' and fenceType ~= 'string' then
                redis.call('DEL', completeKey)
                return redis.error_reply('counter reaction maintenance fence has an invalid Redis type')
              end
              if fenceType == 'string' then
                local fenceValue = redis.call('GET', fenceKey)
                if string.sub(fenceValue, 1, 7) ~= '@dirty:' then
                  if string.sub(fenceValue, 1, 10) == '@prepared:' then
                    redis.call('SET', fenceKey, '@dirty:' .. string.sub(fenceValue, 11))
                  else
                    redis.call('SET', fenceKey, '@dirty:' .. fenceValue)
                  end
                end
              end
              redis.call('DEL', completeKey)
              if cntType ~= 'none' and cntType ~= 'string' then
                redis.call('DEL', cntKey)
              end
              cnt = string.rep(string.char(0), schemaLen * fieldSize)
            end
            local off = idx * fieldSize
            local v = read32be(cnt, off) + delta
            if v < 0 then v = 0 end
            if v > uint32Max then
              return redis.error_reply('counter rebuild would overflow unsigned Int32')
            end
            local seg = write32be(v)
            cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off+fieldSize+1)
            redis.call('MSET', cntKey, cnt, dedupeKey, '1')
            return 1
            """;
}
