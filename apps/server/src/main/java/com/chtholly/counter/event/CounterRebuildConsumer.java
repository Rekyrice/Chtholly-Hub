package com.chtholly.counter.event;

import com.chtholly.common.kafka.AbstractKafkaConsumer;
import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 灾难场景下的计数重建消费者：基于 earliest 回放历史事件，直接折叠到 SDS。
 * 默认关闭，仅当 counter.rebuild.enabled=true 时启用。
 */
@Service
@ConditionalOnProperty(name = "counter.rebuild.enabled", havingValue = "true")
public class CounterRebuildConsumer extends AbstractKafkaConsumer {

    private static final String CONSUMER_GROUP = "counter-rebuild";

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> incrScript;

    public CounterRebuildConsumer(ObjectMapper objectMapper,
                                  StringRedisTemplate redis,
                                  KafkaTemplate<String, String> kafka,
                                  DeadLetterMessageService deadLetterMessageService) {
        super(kafka, objectMapper, deadLetterMessageService);
        this.redis = redis;
        this.incrScript = new DefaultRedisScript<>();
        this.incrScript.setResultType(Long.class);
        this.incrScript.setScriptText(INCR_FIELD_LUA);
    }

    @KafkaListener(
            topics = CounterTopics.EVENTS,
            groupId = CONSUMER_GROUP,
            properties = {"auto.offset.reset=earliest"}
    )
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
        String cntKey = CounterKeys.sdsKey(evt.getEntityType(), evt.getEntityId());
        redis.execute(incrScript, List.of(cntKey),
                String.valueOf(CounterSchema.SCHEMA_LEN),
                String.valueOf(CounterSchema.FIELD_SIZE),
                String.valueOf(evt.getIdx()),
                String.valueOf(evt.getDelta()));
    }

    @Override
    protected String consumerName() {
        return CONSUMER_GROUP;
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
}
