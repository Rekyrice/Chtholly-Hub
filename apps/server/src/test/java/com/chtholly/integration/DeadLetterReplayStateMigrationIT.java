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

/** Executes the dead-letter manual replay state migration against MySQL 8. */
@Testcontainers(disabledWithoutDocker = true)
class DeadLetterReplayStateMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("dead_letter_replay_migration")
            .withUsername("dead_letter")
            .withPassword("dead_letter");

    @Test
    void v27PreservesExistingRowsAndAddsDistinctManualReplayStates()
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            prepareLegacySchema(connection);

            execute(connection);

            assertThat(queryString(connection, """
                    SELECT COLUMN_TYPE
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'dead_letter_messages'
                      AND column_name = 'status'
                    """)).isEqualTo(
                    "enum('PENDING','RETRYING','DEAD','REPLAYING','UNCERTAIN')");
            assertThat(queryLong(connection, """
                    SELECT COUNT(*)
                    FROM dead_letter_messages
                    WHERE status IN ('PENDING', 'RETRYING', 'DEAD')
                    """)).isEqualTo(3L);

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO dead_letter_messages
                            (id, source_topic, message_value, status,
                             replay_attempt_token, replay_started_at,
                             replay_deadline_at)
                        VALUES
                            (4, 'counter-reaction', '{}', 'REPLAYING',
                             'attempt-4', NOW(3),
                             TIMESTAMPADD(MINUTE, 5, NOW(3))),
                            (5, 'counter-reaction', '{}', 'UNCERTAIN',
                             NULL, NULL, NULL)
                        """);
            }
            execute(connection);

            assertThat(queryLong(connection, """
                    SELECT COUNT(*)
                    FROM dead_letter_messages
                    WHERE status IN ('REPLAYING', 'UNCERTAIN')
                    """)).isEqualTo(2L);
            assertThat(queryString(connection, """
                    SELECT replay_attempt_token
                    FROM dead_letter_messages
                    WHERE id = 4
                    """)).isEqualTo("attempt-4");
            assertThat(queryLong(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'dead_letter_messages'
                      AND column_name IN (
                          'replay_attempt_token',
                          'replay_started_at',
                          'replay_deadline_at')
                    """)).isEqualTo(3L);
        }
    }

    private static void prepareLegacySchema(Connection connection)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS dead_letter_messages");
            statement.executeUpdate("""
                    CREATE TABLE dead_letter_messages (
                        id BIGINT UNSIGNED NOT NULL,
                        source_topic VARCHAR(100) NOT NULL,
                        message_key VARCHAR(255) NULL,
                        message_value TEXT NOT NULL,
                        exception_class VARCHAR(255) NULL,
                        exception_message TEXT NULL,
                        retry_count INT NOT NULL DEFAULT 0,
                        status ENUM('PENDING', 'RETRYING', 'DEAD')
                            NOT NULL DEFAULT 'PENDING',
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (id),
                        KEY idx_topic_status (source_topic, status),
                        KEY idx_created (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                      COLLATE=utf8mb4_unicode_ci
                    """);
            statement.executeUpdate("""
                    INSERT INTO dead_letter_messages
                        (id, source_topic, message_value, status)
                    VALUES
                        (1, 'counter-reaction', '{}', 'PENDING'),
                        (2, 'counter-reaction', '{}', 'RETRYING'),
                        (3, 'counter-reaction', '{}', 'DEAD')
                    """);
        }
    }

    private static void execute(Connection connection) {
        ScriptUtils.executeSqlScript(
                connection,
                new FileSystemResource(
                        "db/migration/V27__dead_letter_replay_state.sql"));
    }

    private static long queryLong(Connection connection, String sql)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static String queryString(Connection connection, String sql)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
