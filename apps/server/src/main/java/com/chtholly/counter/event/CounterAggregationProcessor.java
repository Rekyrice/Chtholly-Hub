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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** Applies one Kafka or local batch using the MySQL inbox and snapshot in one transaction. */
    @Transactional
    public int applyBatch(List<CounterEvent> events) {
        return applyBatchWithResult(events).insertedEvents();
    }

    /**
     * Applies one batch and reports reaction events still lacking a durable side-effect receipt.
     *
     * @param events counter events
     * @return durable insert count and pending reaction side effects, including safe broker replays
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public ApplyBatchResult applyBatchWithResult(List<CounterEvent> events) {
        if (events == null || events.isEmpty()) { return new ApplyBatchResult(0, List.of()); }
        List<CounterEvent> copy = List.copyOf(events);
        copy.forEach(CounterAggregationProcessor::validateEvent);

        Map<CounterEntityIdentity, Long> reactionEpochs = lockReactionEpochs(copy);
        Map<SnapshotKey, Long> grouped = new LinkedHashMap<>();
        List<CounterEvent> newViewEvents = new ArrayList<>();
        Map<String, CounterEvent> reactionEvents = new LinkedHashMap<>();
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
            if (isReaction(event)) {
                reactionEvents.putIfAbsent(event.getEventId(), event);
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
        return new ApplyBatchResult(
                applied,
                pendingReactionSideEffects(reactionEvents));
    }

    private List<CounterEvent> pendingReactionSideEffects(
            Map<String, CounterEvent> reactionEvents) {
        if (reactionEvents.isEmpty()) {
            return List.of();
        }
        List<String> pendingIds =
                persistenceMapper.listPendingReactionSideEffectEventIds(
                        List.copyOf(reactionEvents.keySet()));
        if (pendingIds == null) {
            throw new IllegalStateException(
                    "Counter reaction pending side-effect query returned no result");
        }
        Set<String> pending = new LinkedHashSet<>();
        for (String eventId : pendingIds) {
            if (eventId == null
                    || !reactionEvents.containsKey(eventId)
                    || !pending.add(eventId)) {
                throw new IllegalStateException(
                        "Counter reaction pending side-effect query returned an invalid event ID");
            }
        }
        return reactionEvents.entrySet().stream()
                .filter(entry -> pending.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
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
        String completeKey =
                CounterKeys.reactionProjectionCompleteKey(parts[2], parts[3]);
        String fenceKey =
                CounterKeys.factMaintenanceFenceKey(parts[2], parts[3]);

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
                redis.execute(
                        transferFieldScript,
                        List.of(cntKey, aggKey, indexKey, completeKey, fenceKey),
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
    public record ApplyBatchResult(int insertedEvents, List<CounterEvent> sideEffectEvents) {
        public ApplyBatchResult {
            sideEffectEvents = List.copyOf(sideEffectEvents);
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
            if not delta or delta ~= math.floor(delta)
                  or not dedupTtl or dedupTtl <= 0
                  or dedupTtl ~= math.floor(dedupTtl) then
              return redis.error_reply('counter aggregation arguments are invalid')
            end
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
            if redis.call('EXISTS', eventKey) ~= 0 then return 0 end
            local aggTypeReply = redis.call('TYPE', aggKey)
            local aggType =
                  type(aggTypeReply) == 'table' and aggTypeReply['ok'] or aggTypeReply
            local indexTypeReply = redis.call('TYPE', indexKey)
            local indexType =
                  type(indexTypeReply) == 'table' and indexTypeReply['ok'] or indexTypeReply
            if (aggType ~= 'none' and aggType ~= 'hash')
                  or (indexType ~= 'none' and indexType ~= 'set') then
              return redis.error_reply('counter aggregation key has an invalid Redis type')
            end
            local increment = redis.pcall('HINCRBY', aggKey, field, delta)
            if type(increment) == 'table' and increment['err'] then
              return redis.error_reply(increment['err'])
            end
            redis.call('SADD', indexKey, aggKey)
            redis.call('SET', eventKey, '1', 'EX', dedupTtl)
            return 1
            """;

    private static final String TRANSFER_FIELD_LUA = """

            local cntKey = KEYS[1]
            local aggKey = KEYS[2]
            local indexKey = KEYS[3]
            local completeKey = KEYS[4]
            local fenceKey = KEYS[5]
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
            if not delta or delta ~= math.floor(delta)
                  or not idx or idx < 0 or idx >= schemaLen
                  or idx ~= math.floor(idx)
                  or schemaLen ~= 5 or fieldSize ~= 4 then
              return redis.error_reply('counter aggregation field is invalid')
            end
            local uint32Max = 4294967295

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
              return redis.error_reply('counter aggregation would overflow unsigned Int32')
            end
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
