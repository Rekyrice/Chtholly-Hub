package com.chtholly.post.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PostProjectionPersistenceContractTest {

    @Test
    void replayQueryUsesReceiptBackedBoundedCursorAndCommitGracePeriod() throws Exception {
        String mapper = new ClassPathResource("mapper/PostProjectionReceiptMapper.xml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(mapper)
                .contains("LEFT JOIN post_projection_receipt")
                .contains("r.event_id IS NULL")
                .contains("o.id &gt; #{afterId}")
                .contains("o.id &lt;= #{throughId}")
                .contains("MAX(id)")
                .contains("created_at &lt;= TIMESTAMPADD(SECOND, -5, NOW(3))")
                .contains("INSERT IGNORE INTO post_projection_receipt");
    }

    @Test
    void mapperCreatesAndLocksOneDurableCursorPerPost() throws Exception {
        String mapper = new ClassPathResource("mapper/PostProjectionReceiptMapper.xml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(mapper)
                .contains("INSERT IGNORE INTO post_projection_cursor")
                .contains("WHERE post_id = #{postId}")
                .contains("FOR UPDATE")
                .contains("last_event_id = #{eventId}")
                .contains("last_event_id = #{previousEventId}");
    }

    @Test
    void finalSchemaAndMigrationKeepReceiptCoupledToOutboxLifetime() throws Exception {
        Path serverRoot = Path.of("").toAbsolutePath();
        String schema = Files.readString(serverRoot.resolve("db/schema.sql"));
        String migration = Files.readString(serverRoot.resolve(
                "db/migration/V28__post_projection_receipt.sql"));

        assertThat(schema)
                .contains("CREATE TABLE IF NOT EXISTS post_projection_cursor")
                .contains("CREATE TABLE IF NOT EXISTS post_projection_receipt")
                .contains("post_id BIGINT UNSIGNED NOT NULL")
                .contains("FOREIGN KEY (event_id) REFERENCES outbox(id) ON DELETE CASCADE");
        assertThat(migration)
                .contains("CREATE TABLE IF NOT EXISTS post_projection_cursor")
                .contains("CREATE TABLE IF NOT EXISTS post_projection_receipt")
                .contains("post_id BIGINT UNSIGNED NOT NULL")
                .contains("FOREIGN KEY (event_id) REFERENCES outbox(id) ON DELETE CASCADE");
    }
}
