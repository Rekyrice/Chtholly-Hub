package com.chtholly.integration;

import com.chtholly.post.service.PostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden-path contracts for post persistence, Outbox and search propagation.
 */
class PostPublishingGoldenPathIT extends AbstractGoldenPathIT {

    private static final long AUTHOR_ID = 101L;

    @Autowired
    private PostService postService;

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
}
