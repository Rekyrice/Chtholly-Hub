package com.chtholly.common.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the reaction recovery retention boundary with real MySQL syntax. */
@Testcontainers(disabledWithoutDocker = true)
class DataCleanupJobOutboxIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("outbox_cleanup")
            .withUsername("cleanup")
            .withPassword("cleanup");

    private JdbcTemplate jdbc;
    private DataCleanupJob cleanupJob;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS counter_event_inbox");
        jdbc.execute("DROP TABLE IF EXISTS outbox");
        jdbc.execute("""
                CREATE TABLE outbox (
                    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
                    aggregate_type VARCHAR(64) NOT NULL,
                    aggregate_id BIGINT UNSIGNED NULL,
                    type VARCHAR(64) NOT NULL,
                    payload JSON NOT NULL,
                    created_at TIMESTAMP(3) NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE counter_event_inbox (
                    event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
                    entity_type VARCHAR(32) NOT NULL,
                    entity_id VARCHAR(64) NOT NULL,
                    metric VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    delta INT NOT NULL,
                    user_id BIGINT NOT NULL,
                    fact_epoch BIGINT UNSIGNED NOT NULL,
                    applied_at DATETIME(3) NOT NULL,
                    side_effects_published_at DATETIME(3) NULL
                ) ENGINE=InnoDB
                """);
        CleanupProperties properties = new CleanupProperties(
                new CleanupProperties.Retention(90),
                new CleanupProperties.Retention(7),
                new CleanupProperties.Retention(90),
                new CleanupProperties.Retention(30),
                new CleanupProperties.Retention(30),
                new CleanupProperties.FeedPages(1000));
        cleanupJob = new DataCleanupJob(new BatchDeleteService(jdbc), properties);
    }

    @Test
    void deletesOnlyPublishedOrNonReactionRowsAfterRetention() {
        insertOutbox(1L, "counter_reaction", "CounterReactionChanged", true);
        insertOutbox(2L, "counter_reaction", "CounterReactionChanged", true);
        insertInbox(2L, null);
        insertOutbox(3L, "counter_reaction", "CounterReactionChanged", true);
        insertInbox(3L, "2026-01-01 00:00:01.000");
        insertOutbox(4L, "following", "FollowCreated", true);
        insertOutbox(5L, "counter_reaction", "CounterReactionChanged", false);
        insertInbox(5L, "2026-01-01 00:00:01.000");
        insertOutbox(6L, "counter_reaction", "UnexpectedReactionEvent", true);

        assertThat(cleanupJob.cleanOutbox()).isEqualTo(2);

        List<Long> remaining = jdbc.queryForList(
                "SELECT id FROM outbox ORDER BY id", Long.class);
        assertThat(remaining).containsExactly(1L, 2L, 5L, 6L);
    }

    private void insertOutbox(
            long id,
            String aggregateType,
            String type,
            boolean expired) {
        jdbc.update("""
                        INSERT INTO outbox
                            (id, aggregate_type, aggregate_id, type, payload, created_at)
                        VALUES (?, ?, 42, ?, JSON_OBJECT(), ?)
                        """,
                id,
                aggregateType,
                type,
                expired ? "2026-01-01 00:00:00.000" : "2030-01-01 00:00:00.000");
    }

    private void insertInbox(long eventId, String publishedAt) {
        jdbc.update("""
                        INSERT INTO counter_event_inbox
                            (event_id, entity_type, entity_id, metric, delta, user_id,
                             fact_epoch, applied_at, side_effects_published_at)
                        VALUES (?, 'post', '7', 'like', 1, 42, 1,
                                '2026-01-01 00:00:00.000', ?)
                        """,
                Long.toString(eventId),
                publishedAt);
    }
}
