package com.chtholly.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the integration profile uses real infrastructure clients.
 */
class InfrastructureSmokeIT extends AbstractGoldenPathIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Test
    void bootsAgainstRealInfrastructure() throws Exception {
        Long users = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        assertThat(users).isNotNull().isGreaterThanOrEqualTo(0L);

        redisTemplate.opsForValue().set("it:smoke", "ok");
        assertThat(redisTemplate.opsForValue().get("it:smoke")).isEqualTo("ok");

        kafkaTemplate.send("it-smoke", "ok").get(10, TimeUnit.SECONDS);
        assertThat(elasticsearchClient.info().version().number()).startsWith("8.18");
    }
}
