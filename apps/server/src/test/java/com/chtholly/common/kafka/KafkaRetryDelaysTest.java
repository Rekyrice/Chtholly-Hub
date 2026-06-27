package com.chtholly.common.kafka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaRetryDelaysTest {

    @Test
    void usesIncrementalDelaySeconds() {
        long now = System.currentTimeMillis();
        long first = KafkaRetryDelays.deliverAfterEpochMs(1);
        long second = KafkaRetryDelays.deliverAfterEpochMs(2);
        long third = KafkaRetryDelays.deliverAfterEpochMs(3);

        assertThat(first - now).isBetween(4_000L, 6_000L);
        assertThat(second - now).isBetween(29_000L, 31_000L);
        assertThat(third - now).isBetween(119_000L, 121_000L);
    }
}
