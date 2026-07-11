package com.chtholly.integration;

import com.chtholly.post.service.PostService;
import com.chtholly.relation.outbox.OutboxTopics;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies stable HTTP and persistence contracts while Redis or Elasticsearch is unavailable.
 */
@AutoConfigureMockMvc
class DegradationGoldenPathIT extends AbstractGoldenPathIT {

    private static final long USER_ID = 301L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PostService postService;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    void setUpData() {
        cleanRedis();
        cleanDatabase();
        jdbc.update("DELETE FROM dead_letter_messages");
        jdbc.update("INSERT INTO users (id, nickname, handle) VALUES (?, ?, ?)",
                USER_ID, "Degradation User", "degradation-user");
    }

    @Test
    void elasticsearchFailureReturnsDegradedSearchAndKeepsOutboxReplayable() throws Exception {
        ELASTICSEARCH_PROXY.setConnectionCut(true);

        mockMvc.perform(get("/api/v1/search").param("q", "unavailable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.items").isEmpty());

        long postId = postService.createDraft(USER_ID);
        postService.updateMetadata(
                USER_ID, postId, "Recoverable Search Post", null,
                List.of(), List.of(), "public", false, "must remain replayable");
        jdbc.update("DELETE FROM outbox");
        postService.publish(USER_ID, postId);

        Map<String, Object> outbox = jdbc.queryForMap("""
                SELECT id, payload FROM outbox
                WHERE aggregate_type = 'post' AND aggregate_id = ? AND type = 'PostPublished'
                """, postId);
        long eventId = ((Number) outbox.get("id")).longValue();
        String envelope = canalEnvelope(eventId, String.valueOf(outbox.get("payload")));
        kafka.send(OutboxTopics.CANAL_OUTBOX, envelope).get(10, TimeUnit.SECONDS);

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM dead_letter_messages
                        WHERE source_topic = ? AND message_value LIKE ?
                        """, Long.class, OutboxTopics.CANAL_OUTBOX, "%" + eventId + "%"))
                        .isPositive());
        assertThat(redis.hasKey("consumed:outbox:search:" + eventId)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM posts WHERE id = ?", String.class, postId)).isEqualTo("published");

        ELASTICSEARCH_PROXY.setConnectionCut(false);
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(redis.hasKey("consumed:outbox:search:" + eventId)).isTrue());
    }

    @Test
    void redisFailureRejectsLikeButMysqlOnlyDraftStillCommits() throws Exception {
        REDIS_PROXY.setConnectionCut(true);
        try {
            mockMvc.perform(post("/api/v1/action/like")
                            .with(jwt().jwt(token -> token.claim("uid", USER_ID)))
                            .contentType("application/json")
                            .content("""
                                    {"entityType":"post","entityId":"9001"}
                                    """))
                    .andExpect(status().is5xxServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class)).isZero();

            long draftId = postService.createDraft(USER_ID);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM posts WHERE id = ?", String.class, draftId)).isEqualTo("draft");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class)).isZero();
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(redis.getConnectionFactory().getConnection().ping()).isEqualTo("PONG"));
        assertThat(redis.hasKey("bm:like:post:9001:0")).isFalse();
    }
}
