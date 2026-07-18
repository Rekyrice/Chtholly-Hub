package com.chtholly.counter.event;

import com.chtholly.counter.schema.CounterKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterAggregationProcessorTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private CounterAggregationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CounterAggregationProcessor(redis);
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        lenient().when(redis.opsForHash()).thenReturn(hashOps);
    }

    @Test
    void given_indexedAggKey_when_flush_then_usesSetMembersNotKeys() {
        String aggKey = CounterKeys.aggKey("post", "42");
        when(setOps.members(CounterKeys.aggIndexKey())).thenReturn(Set.of(aggKey));
        when(hashOps.entries(aggKey)).thenReturn(Map.of("0", "2"));
        lenient().when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(1L);

        processor.flush();

        verify(setOps).members(CounterKeys.aggIndexKey());
        verify(redis, never()).keys(anyString());
        verify(redis).execute(any(DefaultRedisScript.class), eq(List.of(
                        CounterKeys.sdsKey("post", "42"), aggKey, CounterKeys.aggIndexKey())),
                eq("5"), eq("4"), eq("0"));
    }

    @Test
    void given_flushScriptFails_when_flush_then_keepsAggHash() {
        String aggKey = CounterKeys.aggKey("post", "99");
        when(setOps.members(CounterKeys.aggIndexKey())).thenReturn(Set.of(aggKey));
        when(hashOps.entries(aggKey)).thenReturn(Map.of("1", "3"));
        when(redis.execute(any(DefaultRedisScript.class), eq(List.of(
                        CounterKeys.sdsKey("post", "99"), aggKey, CounterKeys.aggIndexKey())),
                any(), any(), any()))
                .thenThrow(new RuntimeException("lua down"));

        processor.flush();

        verify(redis, never()).delete(aggKey);
        verify(setOps, never()).remove(eq(CounterKeys.aggIndexKey()), eq(aggKey));
    }

    @Test
    void fieldTransferLuaReadsCurrentDeltaAndCleansIndexAtomically() {
        String aggKey = CounterKeys.aggKey("post", "42");
        when(setOps.members(CounterKeys.aggIndexKey())).thenReturn(Set.of(aggKey));
        when(hashOps.entries(aggKey)).thenReturn(Map.of("0", "stale-value-must-not-be-used"));
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any())).thenReturn(3L);

        processor.flush();

        ArgumentCaptor<DefaultRedisScript<Long>> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis).execute(script.capture(), eq(List.of(
                        CounterKeys.sdsKey("post", "42"), aggKey, CounterKeys.aggIndexKey())),
                eq("5"), eq("4"), eq("0"));
        String lua = script.getValue().getScriptAsString();
        assertThat(lua).contains("HGET", "HDEL", "HLEN", "SREM");
        assertThat(lua.indexOf("HGET")).isLessThan(lua.indexOf("SET', cntKey"));
        assertThat(lua).doesNotContain("ARGV[4]");
    }

    @Test
    void given_duplicateEvent_when_applyEvent_then_aggregatesOnlyOnce() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(1L, 0L);
        CounterEvent event = CounterEvent.of("evt-1", "post", "7", "like", 1, 100L, 1);

        assertThat(processor.applyEvent(event)).isTrue();
        assertThat(processor.applyEvent(event)).isFalse();

        verify(redis, times(2)).execute(any(DefaultRedisScript.class),
                eq(List.of(CounterKeys.aggKey("post", "7"), CounterKeys.aggIndexKey(),
                        "counter:event:evt-1", CounterKeys.factEpochKey("post", "7"))),
                eq("1"), eq("1"), eq("604800"), eq("1"), eq("0"));
    }

    @Test
    void givenSameSeedEventIdTwice_whenApply_thenPersistentDedupeAcceptsOnlyFirst() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(1L, 0L);
        CounterEvent event = CounterEvent.of(
                "post", "7", "view", 0, 0L, 10, "seed-view:ns:7:10");

        assertThat(processor.applyEvent(event)).isTrue();
        assertThat(processor.applyEvent(event)).isFalse();

        ArgumentCaptor<DefaultRedisScript<Long>> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis, times(2)).execute(script.capture(), eq(List.of(
                        CounterKeys.aggKey("post", "7"), CounterKeys.aggIndexKey(),
                        "counter:event:seed-view:ns:7:10", CounterKeys.factEpochKey("post", "7"))),
                eq("0"), eq("10"), eq("604800"), eq("0"), eq("0"));
        assertThat(script.getValue().getScriptAsString()).contains("SET", "NX", "EX", "HINCRBY", "SADD");
        assertThat(script.getValue().getScriptAsString().indexOf("SET', eventKey"))
                .isLessThan(script.getValue().getScriptAsString().indexOf("HINCRBY"));
    }

    @Test
    void givenDifferentBaselineEventIds_whenApply_thenBothCanAddTheirDeltas() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any(), any())).thenReturn(1L);
        CounterEvent first = CounterEvent.of(
                "post", "7", "view", 0, 0L, 10, "seed-view:ns:7:10");
        CounterEvent second = CounterEvent.of(
                "post", "7", "view", 0, 0L, 5, "seed-view:ns:7:15");

        assertThat(processor.applyEvent(first)).isTrue();
        assertThat(processor.applyEvent(second)).isTrue();

        verify(redis).execute(any(DefaultRedisScript.class), eq(List.of(
                        CounterKeys.aggKey("post", "7"), CounterKeys.aggIndexKey(),
                        "counter:event:seed-view:ns:7:10", CounterKeys.factEpochKey("post", "7"))),
                eq("0"), eq("10"), eq("604800"), eq("0"), eq("0"));
        verify(redis).execute(any(DefaultRedisScript.class), eq(List.of(
                        CounterKeys.aggKey("post", "7"), CounterKeys.aggIndexKey(),
                        "counter:event:seed-view:ns:7:15", CounterKeys.factEpochKey("post", "7"))),
                eq("0"), eq("5"), eq("604800"), eq("0"), eq("0"));
    }

    @Test
    void generatedEventIdUsesPersistentDedupePath() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any(), any())).thenReturn(1L);
        CounterEvent event = CounterEvent.of("post", "7", "like", 1, 100L, 1);

        assertThat(event.getEventId()).isNotBlank();
        assertThat(processor.applyEvent(event)).isTrue();

        verify(redis).execute(any(DefaultRedisScript.class),
                eq(List.of(CounterKeys.aggKey("post", "7"), CounterKeys.aggIndexKey(),
                        "counter:event:" + event.getEventId(), CounterKeys.factEpochKey("post", "7"))),
                eq("1"), eq("1"), eq("604800"), eq("1"), eq("0"));
    }

    @Test
    void staleReactionEpochIsCheckedBeforePersistentDedupeAndAggregation() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(-1L);
        CounterEvent event = CounterEvent.of("evt-stale", "post", "7", "fav", 2, 100L, 1);
        event.setFactEpoch(3L);

        assertThat(processor.applyEvent(event)).isFalse();

        ArgumentCaptor<DefaultRedisScript<Long>> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis).execute(script.capture(), eq(List.of(
                        CounterKeys.aggKey("post", "7"), CounterKeys.aggIndexKey(),
                        "counter:event:evt-stale", CounterKeys.factEpochKey("post", "7"))),
                eq("2"), eq("1"), eq("604800"), eq("1"), eq("3"));
        String lua = script.getValue().getScriptAsString();
        assertThat(lua.indexOf("currentEpoch ~= expectedEpoch"))
                .isLessThan(lua.indexOf("SET', eventKey"));
    }

    @Test
    void legacyKafkaJsonWithoutEventIdRemainsReadableButCannotBeApplied() throws Exception {
        CounterEvent event = new ObjectMapper().readValue(
                "{\"entityType\":\"post\",\"entityId\":\"7\",\"metric\":\"like\","
                        + "\"idx\":1,\"userId\":9,\"delta\":1}",
                CounterEvent.class);

        assertThat(event.getEventId()).isNull();
        assertThat(event.getDelta()).isEqualTo(1);
        assertThatThrownBy(() -> processor.applyEvent(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ID");
    }
}
