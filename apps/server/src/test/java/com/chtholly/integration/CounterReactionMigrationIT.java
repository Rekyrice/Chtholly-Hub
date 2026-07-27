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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Executes the incremental reaction-fact migration against an otherwise empty MySQL database. */
@Testcontainers(disabledWithoutDocker = true)
class CounterReactionMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("reaction_migration")
            .withUsername("reaction")
            .withPassword("reaction");

    @Test
    void v25CreatesTheExpectedConstraintsAndIsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            executeMigration(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO counter_reaction
                            (entity_type, entity_id, metric, user_id, created_at)
                        VALUES ('post', '7', 'like', 42, NOW(3))
                        """);
            }

            executeMigration(connection);

            assertThat(queryLong(
                    connection, "SELECT COUNT(*) FROM counter_reaction"))
                    .isEqualTo(1L);
            assertThat(queryRows(connection, """
                    SELECT CONCAT(COLUMN_NAME, ':', COLLATION_NAME)
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'counter_reaction'
                      AND COLUMN_NAME IN ('entity_type', 'entity_id', 'metric')
                    ORDER BY ORDINAL_POSITION
                    """)).containsExactly(
                    "entity_type:utf8mb4_bin",
                    "entity_id:utf8mb4_bin",
                    "metric:ascii_bin");
            assertThat(queryRows(connection, """
                    SELECT CONCAT(INDEX_NAME, ':', SEQ_IN_INDEX, ':', COLUMN_NAME)
                    FROM INFORMATION_SCHEMA.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'counter_reaction'
                      AND INDEX_NAME IN ('PRIMARY', 'ix_counter_reaction_user_metric')
                    ORDER BY CASE WHEN INDEX_NAME = 'PRIMARY' THEN 0 ELSE 1 END,
                             SEQ_IN_INDEX
                    """)).containsExactly(
                    "PRIMARY:1:entity_type",
                    "PRIMARY:2:entity_id",
                    "PRIMARY:3:metric",
                    "PRIMARY:4:user_id",
                    "ix_counter_reaction_user_metric:1:user_id",
                    "ix_counter_reaction_user_metric:2:metric",
                    "ix_counter_reaction_user_metric:3:entity_type",
                    "ix_counter_reaction_user_metric:4:entity_id");
            assertThatThrownBy(() -> insertInvalidMetric(connection))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static void executeMigration(Connection connection) {
        ScriptUtils.executeSqlScript(
                connection,
                new FileSystemResource("db/migration/V25__counter_reaction.sql"));
    }

    private static void insertInvalidMetric(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO counter_reaction
                        (entity_type, entity_id, metric, user_id, created_at)
                    VALUES ('post', '7', 'share', 43, NOW(3))
                    """);
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static List<String> queryRows(Connection connection, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return List.copyOf(rows);
    }
}
