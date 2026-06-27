package com.chtholly.counter.event;

import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.schema.CounterSchema;
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
import org.springframework.kafka.core.KafkaTemplate;

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
class CounterAggregationConsumerFlushTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private KafkaTemplate<String, String> kafka;
    @Mock
    private DeadLetterMessageService deadLetterMessageService;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private CounterAggregationConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new CounterAggregationConsumer(new ObjectMapper(), redis, kafka, deadLetterMessageService);
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        lenient().when(redis.opsForHash()).thenReturn(hashOps);
    }

    @Test
    void given_indexedAggKey_when_flush_then_usesSetMembersNotKeys() {
        String aggKey = CounterKeys.aggKey("post", "42");
        when(setOps.members(CounterKeys.aggIndexKey())).thenReturn(Set.of(aggKey));
        when(hashOps.entries(aggKey)).thenReturn(Map.of("0", "2"));
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(1L);
        when(hashOps.size(aggKey)).thenReturn(0L);

        consumer.flush();

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

        consumer.flush();

        verify(redis, never()).delete(aggKey);
        verify(setOps, never()).remove(eq(CounterKeys.aggIndexKey()), eq(aggKey));
    }

    @Test
    void given_event_when_process_then_registersAggKeyViaLua() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(1L);

        CounterEvent event = CounterEvent.of("post", "7", "like", 0, 100L, 1);
        try {
            var method = CounterAggregationConsumer.class.getDeclaredMethod(
                    "process", String.class, String.class, String.class, int.class);
            method.setAccessible(true);
            method.invoke(consumer, "counter-events", null,
                    new ObjectMapper().writeValueAsString(event), 0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(DefaultRedisScript.class), keysCaptor.capture(), eq("0"), eq("1"));
        assertThat(keysCaptor.getValue()).containsExactly(
                CounterKeys.aggKey("post", "7"),
                CounterKeys.aggIndexKey());
    }
}
