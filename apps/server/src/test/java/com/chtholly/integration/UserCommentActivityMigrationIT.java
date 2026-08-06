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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes the user comment activity index migration against MySQL 8. */
@Testcontainers(disabledWithoutDocker = true)
class UserCommentActivityMigrationIT {

    private static final String INDEX_NAME = "ix_comments_user_deleted_ct";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("user_comment_activity_migration")
            .withUsername("comment_activity")
            .withPassword("comment_activity");

    @Test
    void v30ReplaysAfterFullSchemaAndRepairsMissingOrInvalidIndexes() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            execute(connection, "db/schema.sql");

            executeMigration(connection);
            executeMigration(connection);
            assertIndexDefinition(connection);

            try (Statement statement = connection.createStatement()) {
                // 旧库仍需独立 user_id 索引承载外键，才能移除目标索引。
                statement.executeUpdate(
                        "ALTER TABLE comments ADD KEY ix_comments_user_legacy (user_id)");
                statement.executeUpdate("ALTER TABLE comments DROP INDEX " + INDEX_NAME);
            }

            executeMigration(connection);
            executeMigration(connection);
            assertIndexDefinition(connection);

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE comments DROP INDEX " + INDEX_NAME);
                statement.executeUpdate("""
                        ALTER TABLE comments
                        ADD KEY ix_comments_user_deleted_ct (user_id, created_at)
                        """);
                statement.executeUpdate(
                        "ALTER TABLE comments DROP INDEX ix_comments_user_legacy");
            }

            executeMigration(connection);
            executeMigration(connection);
            assertIndexDefinition(connection);
        }
    }

    private static void assertIndexDefinition(Connection connection) throws Exception {
        assertThat(queryRows(connection, """
                SELECT column_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'comments'
                  AND index_name = 'ix_comments_user_deleted_ct'
                ORDER BY seq_in_index
                """)).containsExactly("user_id", "deleted_at", "created_at", "id");
        assertThat(queryLong(connection, """
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'comments'
                  AND index_name = 'ix_comments_user_deleted_ct'
                  AND sub_part IS NULL
                  AND is_visible = 'YES'
                  AND index_type = 'BTREE'
                  AND non_unique = 1
                """)).isEqualTo(4L);
    }

    private static void executeMigration(Connection connection) {
        execute(connection, "db/migration/V30__user_comment_activity_index.sql");
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

    private static List<String> queryRows(Connection connection, String sql) throws Exception {
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
