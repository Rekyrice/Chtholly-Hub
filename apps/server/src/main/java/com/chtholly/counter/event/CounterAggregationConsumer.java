package com.chtholly.counter.event;

import com.chtholly.common.kafka.AbstractKafkaConsumer;
import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.List;

/**
 * 计数事件聚合与刷写消费者。
 *
 * <p>职责：</p>
 * - 消费点赞/收藏等增量事件，写入 Redis 聚合桶（Hash）；
 * - 以固定延迟定时任务将聚合增量折叠到 SDS 固定结构计数；
 * - 刷写成功后删除聚合字段，避免重复加算。
 */
@Service
public class CounterAggregationConsumer extends AbstractKafkaConsumer {

    private static final String CONSUMER_GROUP = "counter-agg";

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> incrScript;
    private final DefaultRedisScript<Long> decrScript;

    public CounterAggregationConsumer(ObjectMapper objectMapper,
                                      StringRedisTemplate redis,
                                      KafkaTemplate<String, String> kafka,
                                      DeadLetterMessageService deadLetterMessageService) {
        super(kafka, objectMapper, deadLetterMessageService);
        this.redis = redis;
        this.incrScript = new DefaultRedisScript<>();
        this.incrScript.setResultType(Long.class);
        this.incrScript.setScriptText(INCR_FIELD_LUA);

        this.decrScript = new DefaultRedisScript<>();
        this.decrScript.setResultType(Long.class);
        this.decrScript.setScriptText(DECR_FIELD_LUA);
    }

    @KafkaListener(topics = CounterTopics.EVENTS, groupId = CONSUMER_GROUP)
    public void onMessage(String message, Acknowledgment ack) {
        consumeMessage(CounterTopics.EVENTS, null, message, ack);
    }

    @KafkaListener(topics = CounterTopics.EVENTS + "-retry", groupId = CONSUMER_GROUP + "-retry")
    public void onRetryMessage(String message, Acknowledgment ack) {
        consumeRetryEnvelope(message, ack);
    }

    @Override
    protected void process(String sourceTopic, String messageKey, String payload, int retryCount) throws Exception {
        CounterEvent evt = objectMapper.readValue(payload, CounterEvent.class);
        String aggKey = CounterKeys.aggKey(evt.getEntityType(), evt.getEntityId());
        String field = String.valueOf(evt.getIdx());
        redis.opsForHash().increment(aggKey, field, evt.getDelta());
    }

    @Override
    protected String consumerName() {
        return CONSUMER_GROUP;
    }

    /**
     * 将聚合增量刷写到 SDS 固定结构计数。
     * 固定延迟 1s，保证秒级最终一致性。
     */
    @Scheduled(fixedDelay = 1000L)
    public void flush() {
        Set<String> keys = redis.keys("agg:" + CounterSchema.SCHEMA_ID + ":*");
        if (keys.isEmpty()) {
            return;
        }

        for (String aggKey : keys) {
            Map<Object, Object> entries = redis.opsForHash().entries(aggKey);
            if (entries.isEmpty()) {
                continue;
            }
            String[] parts = aggKey.split(":", 4);
            if (parts.length < 4) {
                continue;
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
                    // 留存字段，下一轮 flush 重试
                }
            }
            Long size = redis.opsForHash().size(aggKey);
            if (size == 0L) {
                redis.delete(aggKey);
            }
        }
    }

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
