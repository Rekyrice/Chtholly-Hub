package com.chtholly.counter.event;

import com.chtholly.common.kafka.deadletter.DeadLetterMessageService;
import com.chtholly.counter.schema.CounterKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CounterRebuildConsumerTest {

    @Mock private StringRedisTemplate redis;
    @Mock private KafkaTemplate<String, String> kafka;
    @Mock private DeadLetterMessageService deadLetters;

    private CounterRebuildConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new CounterRebuildConsumer(new ObjectMapper(), redis, kafka, deadLetters);
    }

    @Test
    void sameEventIdDuringReplayRebuildsOnlyOnce() {
        when(redis.execute(any(DefaultRedisScript.class), any(List.class), any(), any(), any(), any()))
                .thenReturn(1L, 0L);
        CounterEvent event = CounterEvent.of("post", "7", "view", 0, 0L, 10, "seed-view:ns:7:10");

        assertThat(consumer.applyRebuildEvent(event)).isTrue();
        assertThat(consumer.applyRebuildEvent(event)).isFalse();

        ArgumentCaptor<DefaultRedisScript<Long>> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis, org.mockito.Mockito.times(2)).execute(script.capture(), eq(List.of(
                CounterKeys.sdsKey("post", "7"), CounterKeys.eventDedupeKey("seed-view:ns:7:10"))),
                eq("5"), eq("4"), eq("0"), eq("10"));
        assertThat(script.getValue().getScriptAsString())
                .contains("SETNX", "redis.call('SET', cntKey, cnt)")
                .doesNotContain("EXPIRE", "PEXPIRE");
    }

    @Test
    void differentEventIdsCanBothRebuildAndLegacyEventUsesOldPath() {
        when(redis.execute(any(DefaultRedisScript.class), any(List.class), any(), any(), any(), any()))
                .thenReturn(1L);
        CounterEvent first = CounterEvent.of("post", "7", "view", 0, 0L, 10, "baseline-10");
        CounterEvent second = CounterEvent.of("post", "7", "view", 0, 0L, 5, "baseline-15");
        CounterEvent legacy = CounterEvent.of("post", "7", "like", 1, 9L, 1);

        assertThat(consumer.applyRebuildEvent(first)).isTrue();
        assertThat(consumer.applyRebuildEvent(second)).isTrue();
        assertThat(consumer.applyRebuildEvent(legacy)).isTrue();

        verify(redis).execute(any(DefaultRedisScript.class), eq(List.of(
                CounterKeys.sdsKey("post", "7"), CounterKeys.eventDedupeKey("baseline-10"))),
                eq("5"), eq("4"), eq("0"), eq("10"));
        verify(redis).execute(any(DefaultRedisScript.class), eq(List.of(
                CounterKeys.sdsKey("post", "7"), CounterKeys.eventDedupeKey("baseline-15"))),
                eq("5"), eq("4"), eq("0"), eq("5"));
        verify(redis).execute(any(DefaultRedisScript.class), eq(List.of(CounterKeys.sdsKey("post", "7"))),
                eq("5"), eq("4"), eq("1"), eq("1"));
    }

    @Test
    void legacyJsonWithoutEventIdUsesBackwardCompatibleReplay() throws Exception {
        when(redis.execute(any(DefaultRedisScript.class), any(List.class), any(), any(), any(), any()))
                .thenReturn(1L);
        CounterEvent legacy = new ObjectMapper().readValue(
                "{\"entityType\":\"post\",\"entityId\":\"7\",\"metric\":\"like\","
                        + "\"idx\":1,\"userId\":9,\"delta\":1}", CounterEvent.class);

        assertThat(consumer.applyRebuildEvent(legacy)).isTrue();

        verify(redis).execute(any(DefaultRedisScript.class), eq(List.of(CounterKeys.sdsKey("post", "7"))),
                eq("5"), eq("4"), eq("1"), eq("1"));
    }
}
