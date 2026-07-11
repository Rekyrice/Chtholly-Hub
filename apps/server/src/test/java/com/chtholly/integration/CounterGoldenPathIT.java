package com.chtholly.integration;

import com.chtholly.counter.event.CounterAggregationProcessor;
import com.chtholly.counter.event.CounterEvent;
import com.chtholly.counter.event.CounterTopics;
import com.chtholly.counter.schema.BitmapShard;
import com.chtholly.counter.schema.CounterKeys;
import com.chtholly.counter.service.CounterService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Redis Bitmap to Kafka aggregation path against real infrastructure.
 */
class CounterGoldenPathIT extends AbstractGoldenPathIT {

    private static final String AGGREGATION_GROUP = "counter-agg";

    @Autowired
    private CounterService counterService;

    @Autowired
    private CounterAggregationProcessor aggregationProcessor;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    void resetState() {
        cleanRedis();
        cleanDatabase();
    }

    @Test
    void duplicateKafkaDeliveryConvergesToOneLikeAndOneBitmapBit() throws Exception {
        String entityId = "7001";
        long userId = 42L;
        AtomicReference<String> capturedPayload = new AtomicReference<>();

        try (KafkaConsumer<String, String> probe = newProbeConsumer()) {
            probe.subscribe(List.of(CounterTopics.EVENTS));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
                probe.poll(Duration.ofMillis(100));
                return !probe.assignment().isEmpty();
            });

            assertThat(counterService.like("post", entityId, userId)).isTrue();
            Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> {
                probe.poll(Duration.ofMillis(250)).forEach(record -> capturedPayload.compareAndSet(null, record.value()));
                return capturedPayload.get() != null;
            });
        }

        CounterEvent emitted = objectMapper.readValue(capturedPayload.get(), CounterEvent.class);
        assertThat(emitted.getEventId()).isNotBlank();

        // 重放完全相同的业务事件，验证消费者侧按事件 ID 去重，而不是依赖 Kafka offset。
        kafka.send(CounterTopics.EVENTS, capturedPayload.get()).get(10, TimeUnit.SECONDS);
        awaitAggregationConsumerCaughtUp();
        aggregationProcessor.flush();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(counterService.getCounts("post", entityId, List.of("like")))
                        .containsEntry("like", 1L));

        String bitmapKey = CounterKeys.bitmapKey(
                "like", "post", entityId, BitmapShard.chunkOf(userId));
        Long bitCount = redis.execute((RedisCallback<Long>) connection ->
                connection.stringCommands().bitCount(bitmapKey.getBytes(StandardCharsets.UTF_8)));

        assertThat(bitCount).isEqualTo(1L);
        assertThat(redis.hasKey("counter:event:" + emitted.getEventId())).isTrue();
        assertThat(redis.opsForHash().entries(CounterKeys.aggKey("post", entityId))).isEmpty();
    }

    private KafkaConsumer<String, String> newProbeConsumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "counter-it-probe-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(properties);
    }

    private void awaitAggregationConsumerCaughtUp() {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(properties)) {
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> {
                Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed = admin
                        .listConsumerGroupOffsets(AGGREGATION_GROUP)
                        .partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS);
                Map<TopicPartition, org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo> ends = admin
                        .listOffsets(committed.keySet().stream().collect(java.util.stream.Collectors.toMap(
                                partition -> partition,
                                partition -> org.apache.kafka.clients.admin.OffsetSpec.latest())))
                        .all()
                        .get(5, TimeUnit.SECONDS);
                return !committed.isEmpty() && committed.entrySet().stream()
                        .allMatch(entry -> entry.getValue().offset() >= ends.get(entry.getKey()).offset());
            });
        }
    }
}
