package com.chtholly.counter.event;

import com.chtholly.counter.schema.CounterKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
        lenient().when(redis.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(1L);
        when(hashOps.size(aggKey)).thenReturn(0L);

        processor.flush();

        verify(setOps).members(CounterKeys.aggIndexKey());
        verify(redis, never()).keys(anyString());
    }

    @Test
    void given_flushScriptFails_when_flush_then_keepsAggHash() {
        String aggKey = CounterKeys.aggKey("post", "99");
        when(setOps.members(CounterKeys.aggIndexKey())).thenReturn(Set.of(aggKey));
        when(hashOps.entries(aggKey)).thenReturn(Map.of("1", "3"));
        when(redis.execute(any(DefaultRedisScript.class), eq(List.of(CounterKeys.sdsKey("post", "99"))),
                any(), any(), any(), any()))
                .thenThrow(new RuntimeException("lua down"));
        when(hashOps.size(aggKey)).thenReturn(1L);

        processor.flush();

        verify(redis, never()).delete(aggKey);
        verify(setOps, never()).remove(eq(CounterKeys.aggIndexKey()), eq(aggKey));
    }

    @Test
    void given_duplicateEvent_when_applyEvent_then_aggregatesOnlyOnce() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(1L, 0L);

        CounterEvent event = CounterEvent.of("evt-1", "post", "7", "like", 0, 100L, 1);

        assertThat(processor.applyEvent(event)).isTrue();
        assertThat(processor.applyEvent(event)).isFalse();
        verify(redis, org.mockito.Mockito.times(2)).execute(any(DefaultRedisScript.class),
                eq(List.of(
                        CounterKeys.aggKey("post", "7"),
                        CounterKeys.aggIndexKey(),
                        "counter:event:evt-1")),
                eq("0"), eq("1"), eq("604800"));
    }
}
