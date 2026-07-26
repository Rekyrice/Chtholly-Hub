package com.chtholly.counter.event;

import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.mapper.CounterPersistenceMapper;
import com.chtholly.counter.mapper.CounterEntityIdentity;
import com.chtholly.counter.mapper.CounterSnapshotDelta;
import com.chtholly.counter.mapper.CounterSnapshotEpoch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Comparator;

/**
 * 计数事件聚合与刷写核心逻辑（Kafka / Spring Event 共用）。
 */
@Service
public class CounterAggregationProcessor {

    private static final Logger log = LoggerFactory.getLogger(CounterAggregationProcessor.class);
    private static final String EVENT_KEY_PREFIX = "counter:event:";
    private static final String EVENT_DEDUP_TTL_SECONDS = "604800";

    private final StringRedisTemplate redis;
    private final CounterPersistenceMapper persistenceMapper;
    private final DefaultRedisScript<Long> aggIncrScript;
    private final DefaultRedisScript<Long> transferFieldScript;
    private final DefaultRedisScript<Long> cleanupEmptyAggScript;

    public CounterAggregationProcessor(
            StringRedisTemplate redis,
            CounterPersistenceMapper persistenceMapper) {
        this.redis = redis;
        this.persistenceMapper = persistenceMapper;
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

    /** Applies one Kafka batch using the MySQL inbox and snapshot in one transaction. */
    @Transactional
    public int applyBatch(List<CounterEvent> events) {
        return applyBatchWithResult(events).insertedEvents();
    }

    /**
     * Applies one batch and reports reaction events that still belong to the current absolute epoch.
     *
     * @param events counter events
     * @return durable insert count and current reaction events, including safe broker replays
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApplyBatchResult applyBatchWithResult(List<CounterEvent> events) {
        if (events == null || events.isEmpty()) { return new ApplyBatchResult(0, List.of()); }
        List<CounterEvent> copy = List.copyOf(events);
        copy.forEach(CounterAggregationProcessor::validateEvent);

        Map<CounterEntityIdentity, Long> reactionEpochs = lockReactionEpochs(copy);
        Map<SnapshotKey, Long> grouped = new LinkedHashMap<>();
        List<CounterEvent> newViewEvents = new ArrayList<>();
        Map<String, CounterEvent> currentReactionEvents = new LinkedHashMap<>();
        int applied = 0;
        for (CounterEvent event : copy) {
            int inserted = persistenceMapper.insertInbox(event);
            boolean newlyInserted;
            if (inserted == 0) {
                if (persistenceMapper.countMatchingInbox(event) != 1) {
                    throw new IllegalStateException("Counter event ID collision detected");
                }
                newlyInserted = false;
            } else if (inserted == 1) {
                newlyInserted = true;
                applied++;
            } else {
                throw new IllegalStateException("Counter inbox insert returned an invalid row count");
            }
            boolean current = isCurrent(event, reactionEpochs);
            if (isReaction(event) && current) {
                currentReactionEvents.putIfAbsent(event.getEventId(), event);
            }
            if (!newlyInserted || !current) {
                continue;
            }
            SnapshotKey key = new SnapshotKey(
                    event.getEntityType(), event.getEntityId(), event.getMetric(), event.getFactEpoch());
            grouped.merge(key, (long) event.getDelta(), Math::addExact);
            if ("view".equals(event.getMetric())) { newViewEvents.add(event); }
        }
        if (!grouped.isEmpty()) {
            List<CounterSnapshotDelta> deltas = grouped.entrySet().stream()
                    .map(entry -> new CounterSnapshotDelta(
                            entry.getKey().entityType(), entry.getKey().entityId(),
                            entry.getKey().metric(), entry.getValue(), entry.getKey().factEpoch()))
                    .toList();
            persistenceMapper.incrementSnapshots(deltas);
            newViewEvents.forEach(this::applyViewEvent);
        }
        return new ApplyBatchResult(applied, List.copyOf(currentReactionEvents.values()));
    }

    private Map<CounterEntityIdentity, Long> lockReactionEpochs(List<CounterEvent> events) {
        List<CounterEntityIdentity> identities = events.stream()
                .filter(CounterAggregationProcessor::isReaction)
                .map(event -> new CounterEntityIdentity(event.getEntityType(), event.getEntityId()))
                .distinct()
                .sorted(Comparator.comparing(CounterEntityIdentity::entityType)
                        .thenComparing(CounterEntityIdentity::entityId))
                .toList();
        if (identities.isEmpty()) {
            return Map.of();
        }
        persistenceMapper.ensureReactionSnapshotsBatch(identities);
        List<CounterSnapshotEpoch> rows = persistenceMapper.lockReactionSnapshotEpochs(identities);
        if (rows == null || rows.size() != identities.size() * 2) {
            throw new IllegalStateException("Counter reaction snapshot lock is incomplete");
        }
        Map<CounterEntityIdentity, Long> epochs = new LinkedHashMap<>();
        Map<CounterEntityIdentity, Integer> counts = new LinkedHashMap<>();
        for (CounterSnapshotEpoch row : rows) {
            if (row == null || row.factEpoch() < 0L
                    || (!"like".equals(row.metric()) && !"fav".equals(row.metric()))) {
                throw new IllegalStateException("Counter reaction snapshot epoch row is invalid");
            }
            CounterEntityIdentity identity =
                    new CounterEntityIdentity(row.entityType(), row.entityId());
            Long previous = epochs.putIfAbsent(identity, row.factEpoch());
            if (previous != null && previous != row.factEpoch()) {
                throw new IllegalStateException("Counter reaction snapshot epoch is inconsistent");
            }
            counts.merge(identity, 1, Integer::sum);
        }
        for (CounterEntityIdentity identity : identities) {
            if (counts.getOrDefault(identity, 0) != 2 || !epochs.containsKey(identity)) {
                throw new IllegalStateException("Counter reaction snapshot lock is incomplete");
            }
        }
        return Map.copyOf(epochs);
    }

    private static boolean isCurrent(
            CounterEvent event,
            Map<CounterEntityIdentity, Long> reactionEpochs) {
        if (!isReaction(event)) {
            return true;
        }
        Long current = reactionEpochs.get(
                new CounterEntityIdentity(event.getEntityType(), event.getEntityId()));
        return current != null && current == event.getFactEpoch();
    }

    private static boolean isReaction(CounterEvent event) {
        return "like".equals(event.getMetric()) || "fav".equals(event.getMetric());
    }

    private boolean applyViewEvent(CounterEvent evt) {
        String aggKey = CounterKeys.aggKey(evt.getEntityType(), evt.getEntityId());
        String indexKey = CounterKeys.aggIndexKey();
        String eventKey = EVENT_KEY_PREFIX + evt.getEventId();
        String field = String.valueOf(evt.getIdx());
        Long applied = redis.execute(
                aggIncrScript,
                List.of(aggKey, indexKey, eventKey,
                        CounterKeys.factEpochKey(evt.getEntityType(), evt.getEntityId())),
                field,
                String.valueOf(evt.getDelta()),
                EVENT_DEDUP_TTL_SECONDS,
                "0",
                String.valueOf(evt.getFactEpoch()));
        return applied != null && applied == 1L;
    }

    private static void validateEvent(CounterEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getEventId() == null || event.getEventId().isBlank() || event.getEventId().length() > 128) {
            throw new IllegalArgumentException("Counter event ID is required and must fit the inbox key");
        }
        if (!event.getEventId().chars().allMatch(value -> value <= 0x7f)) {
            throw new IllegalArgumentException("Counter event ID must contain US-ASCII characters only");
        }
        CounterSchema.requirePersistableIdentity(event.getEntityType(), event.getEntityId());
        Integer expectedIndex = CounterSchema.NAME_TO_IDX.get(event.getMetric());
        if (expectedIndex == null || expectedIndex != event.getIdx()) {
            throw new IllegalArgumentException("Counter event metric and index do not match");
        }
        if (event.getDelta() == 0 || event.getFactEpoch() < 0L) {
            throw new IllegalArgumentException("Counter event delta and fact epoch are invalid");
        }
        if (("like".equals(event.getMetric()) || "fav".equals(event.getMetric()))
                && (Math.abs(event.getDelta()) != 1 || event.getUserId() <= 0L)) {
            throw new IllegalArgumentException("Reaction counter event mutation is invalid");
        }
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
            if (!String.valueOf(CounterSchema.IDX_VIEW).equals(field)) {
                redis.opsForHash().delete(aggKey, field);
                continue;
            }
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

    private record SnapshotKey(String entityType, String entityId, String metric, long factEpoch) {}

    /** Detailed durable application result used by the reaction Outbox projection. */
    public record ApplyBatchResult(int insertedEvents, List<CounterEvent> currentReactionEvents) {
        public ApplyBatchResult {
            currentReactionEvents = List.copyOf(currentReactionEvents);
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
