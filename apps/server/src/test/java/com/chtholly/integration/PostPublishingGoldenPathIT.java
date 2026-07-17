package com.chtholly.integration;

import com.chtholly.post.service.PostService;
import com.chtholly.relation.outbox.OutboxTopics;
import com.chtholly.search.service.SearchService;
import com.chtholly.search.service.SearchSort;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden-path contracts for post persistence, Outbox and search propagation.
 */
class PostPublishingGoldenPathIT extends AbstractGoldenPathIT {

    private static final long AUTHOR_ID = 101L;

    @Autowired
    private PostService postService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    void setUpData() {
        cleanRedis();
        cleanDatabase();
        jdbc.update("INSERT INTO users (id, nickname, handle) VALUES (?, ?, ?)",
                AUTHOR_ID, "Integration Author", "integration-author");
    }

    @Test
    void rollsBackPublishWhenOutboxInsertFails() {
        long postId = postService.createDraft(AUTHOR_ID);
        postService.updateMetadata(
                AUTHOR_ID, postId, "Atomic Publish", null,
                List.of(), List.of(), "public", false, "atomic publish");
        jdbc.update("DELETE FROM outbox");

        jdbc.execute("""
                CREATE TRIGGER it_fail_outbox BEFORE INSERT ON outbox
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced outbox failure'
                """);
        try {
            assertThatThrownBy(() -> postService.publish(AUTHOR_ID, postId))
                    .hasMessageContaining("forced outbox failure");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS it_fail_outbox");
        }

        assertThat(jdbc.queryForObject(
                "SELECT status FROM posts WHERE id = ?", String.class, postId))
                .isEqualTo("draft");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class)).isZero();
    }

    @Test
    void committedPublishBecomesSearchableThroughOutboxAfterElasticsearchRecovers() throws Exception {
        long postId = postService.createDraft(AUTHOR_ID);
        postService.updateMetadata(
                AUTHOR_ID, postId, "Golden Kafka Search", null,
                List.of(), List.of(), "public", false, "outbox indexed description");
        jdbc.update("DELETE FROM outbox");

        ELASTICSEARCH_PROXY.setConnectionCut(true);
        postService.publish(AUTHOR_ID, postId);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM posts WHERE id = ?", String.class, postId))
                .isEqualTo("published");
        Map<String, Object> outbox = jdbc.queryForMap("""
                SELECT id, payload FROM outbox
                WHERE aggregate_type = 'post' AND aggregate_id = ? AND type = 'PostPublished'
                """, postId);
        long eventId = ((Number) outbox.get("id")).longValue();
        String payload = String.valueOf(outbox.get("payload"));

        ELASTICSEARCH_PROXY.setConnectionCut(false);
        kafka.send(OutboxTopics.CANAL_OUTBOX, canalEnvelope(eventId, payload)).get();

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var result = searchService.search(
                    "Golden Kafka Search", 10, null, null, SearchSort.RELEVANCE, null);
            assertThat(result.degraded()).isFalse();
            assertThat(result.items()).anySatisfy(item -> {
                assertThat(item.id()).isEqualTo(String.valueOf(postId));
                assertThat(item.title()).isEqualTo("Golden Kafka Search");
            });
            assertThat(redis.hasKey("consumed:outbox:search:" + eventId)).isTrue();
        });
    }
}
