package com.chtholly.integration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes the reaction side-effect receipt migration against MySQL 8. */
@Testcontainers(disabledWithoutDocker = true)
class CounterReactionSideEffectReceiptMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("reaction_receipt_migration")
            .withUsername("reaction")
            .withPassword("reaction");

    @Test
    void v26BackfillsExistingReactionRowsAndLeavesNewRowsPending() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            prepareLegacySchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO counter_event_inbox
                            (event_id, entity_type, entity_id, metric, delta,
                             user_id, fact_epoch, applied_at)
                        VALUES ('41', 'post', '7', 'like', 1, 42, 0, NOW(3))
                        """);
            }

            execute(connection, "db/migration/V26__counter_reaction_side_effect_receipt.sql");
            assertThat(queryLong(connection, """
                    SELECT COUNT(*)
                    FROM counter_event_inbox
                    WHERE event_id = '41' AND side_effects_published_at = applied_at
                    """)).isEqualTo(1L);
            assertThat(queryLong(connection, """
                    SELECT COUNT(*)
                    FROM schema_migrations
                    WHERE version = 'V26__counter_reaction_receipt_backfill'
                    """)).isEqualTo(1L);

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO counter_event_inbox
                            (event_id, entity_type, entity_id, metric, delta,
                             user_id, fact_epoch, applied_at)
                        VALUES ('42', 'post', '7', 'like', -1, 42, 0, NOW(3))
                        """);
                statement.executeUpdate(
                        "ALTER TABLE outbox DROP INDEX ix_outbox_reaction_replay");
            }
            assertThat(queryLong(connection, """
                    SELECT COUNT(*)
                    FROM counter_event_inbox
                    WHERE event_id = '42' AND side_effects_published_at IS NULL
                    """)).isEqualTo(1L);

            execute(connection, "db/migration/V26__counter_reaction_side_effect_receipt.sql");
            assertThat(queryLong(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'counter_event_inbox'
                      AND column_name = 'side_effects_published_at'
                    """)).isEqualTo(1L);
            assertThat(queryLong(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'outbox'
                      AND index_name = 'ix_outbox_reaction_replay'
                    """)).isEqualTo(3L);
            assertThat(queryString(connection, """
                    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'outbox'
                      AND index_name = 'ix_outbox_reaction_replay'
                    """)).isEqualTo("aggregate_type,type,id");
            assertThat(queryLong(connection, """
                    SELECT COUNT(*)
                    FROM counter_event_inbox
                    WHERE event_id = '42' AND side_effects_published_at IS NULL
                    """)).isEqualTo(1L);
        }
    }

    @Test
    void v26RepairsAnExistingReplayIndexWithTheWrongColumnOrder()
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            prepareLegacySchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        ALTER TABLE outbox
                        ADD KEY ix_outbox_reaction_replay (type, aggregate_type, id)
                        """);
            }

            execute(connection, "db/migration/V26__counter_reaction_side_effect_receipt.sql");

            assertThat(queryString(connection, """
                    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'outbox'
                      AND index_name = 'ix_outbox_reaction_replay'
                    """)).isEqualTo("aggregate_type,type,id");
        }
    }

    @Test
    void v26RepairsAnExistingReplayIndexThatIsPrefixedAndInvisible()
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            prepareLegacySchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        ALTER TABLE outbox
                        ADD KEY ix_outbox_reaction_replay
                            (aggregate_type(8), type, id) INVISIBLE
                        """);
            }

            execute(connection, "db/migration/V26__counter_reaction_side_effect_receipt.sql");

            assertThat(queryLong(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'outbox'
                      AND index_name = 'ix_outbox_reaction_replay'
                      AND sub_part IS NOT NULL
                    """)).isZero();
            assertThat(queryString(connection, """
                    SELECT MIN(is_visible)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'outbox'
                      AND index_name = 'ix_outbox_reaction_replay'
                    """)).isEqualTo("YES");
            assertThat(queryString(connection, """
                    SELECT MIN(index_type)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'outbox'
                      AND index_name = 'ix_outbox_reaction_replay'
                    """)).isEqualTo("BTREE");
        }
    }

    @Test
    void v26CompletesBackfillAfterColumnDdlCommittedBeforePreviousFailure()
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            prepareLegacySchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        ALTER TABLE counter_event_inbox
                        ADD COLUMN side_effects_published_at DATETIME(3) NULL AFTER applied_at
                        """);
                statement.executeUpdate("""
                        INSERT INTO counter_event_inbox
                            (event_id, entity_type, entity_id, metric, delta,
                             user_id, fact_epoch, applied_at, side_effects_published_at)
                        VALUES ('51', 'post', '9', 'fav', 1, 42, 0, NOW(3), NULL)
                        """);
            }

            execute(connection, "db/migration/V26__counter_reaction_side_effect_receipt.sql");

            assertThat(queryLong(connection, """
                    SELECT COUNT(*)
                    FROM counter_event_inbox
                    WHERE event_id = '51' AND side_effects_published_at = applied_at
                    """)).isEqualTo(1L);
            assertThat(queryLong(connection, """
                    SELECT COUNT(*)
                    FROM schema_migrations
                    WHERE version = 'V26__counter_reaction_receipt_backfill'
                    """)).isEqualTo(1L);
        }
    }

    private static void prepareLegacySchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS counter_event_inbox");
            statement.executeUpdate("DROP TABLE IF EXISTS counter_snapshots");
            statement.executeUpdate("DROP TABLE IF EXISTS outbox");
            statement.executeUpdate("DROP TABLE IF EXISTS schema_migrations");
            statement.executeUpdate("""
                    CREATE TABLE schema_migrations (
                        version VARCHAR(64) NOT NULL PRIMARY KEY,
                        applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        }
        execute(connection, "db/migration/V23__counter_event_inbox_and_snapshot.sql");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE outbox (
                        id BIGINT UNSIGNED NOT NULL,
                        aggregate_type VARCHAR(64) NOT NULL,
                        aggregate_id BIGINT UNSIGNED NULL,
                        type VARCHAR(64) NOT NULL,
                        payload JSON NOT NULL,
                        created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        PRIMARY KEY (id),
                        KEY ix_outbox_agg (aggregate_type, aggregate_id),
                        KEY ix_outbox_ct (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        }
    }

    private static void execute(Connection connection, String path) {
        ScriptUtils.executeSqlScript(connection, new FileSystemResource(path));
    }

    private static long queryLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
