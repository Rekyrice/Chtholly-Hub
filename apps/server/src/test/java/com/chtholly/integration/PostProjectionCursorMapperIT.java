package com.chtholly.integration;

import com.chtholly.post.outbox.PostProjectionReceiptMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies that the per-post cursor is a real MySQL cross-session ordering lock. */
@Testcontainers(disabledWithoutDocker = true)
class PostProjectionCursorMapperIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("post_projection")
            .withUsername("post")
            .withPassword("post");

    private static SqlSessionFactory sqlSessionFactory;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE outbox (
                    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
                    aggregate_type VARCHAR(64) NOT NULL,
                    aggregate_id BIGINT UNSIGNED NOT NULL,
                    type VARCHAR(128) NOT NULL,
                    payload JSON NOT NULL,
                    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE post_projection_cursor (
                    post_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
                    last_event_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    updated_at DATETIME(3) NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE post_projection_receipt (
                    event_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
                    post_id BIGINT UNSIGNED NOT NULL,
                    completed_at DATETIME(3) NOT NULL,
                    CONSTRAINT fk_projection_receipt_outbox
                        FOREIGN KEY (event_id) REFERENCES outbox(id) ON DELETE CASCADE
                ) ENGINE=InnoDB
                """);

        Configuration configuration = new Configuration(new Environment(
                "post-projection-cursor-it", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(PostProjectionReceiptMapper.class);
        try (InputStream input = PostProjectionCursorMapperIT.class.getResourceAsStream(
                "/mapper/PostProjectionReceiptMapper.xml")) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(
                    input,
                    configuration,
                    "mapper/PostProjectionReceiptMapper.xml",
                    configuration.getSqlFragments())
                    .parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void clearRows() {
        jdbc.execute("DELETE FROM post_projection_receipt");
        jdbc.execute("DELETE FROM post_projection_cursor");
        jdbc.execute("DELETE FROM outbox");
    }

    @Test
    void selectForUpdateSerializesTwoIndependentSessionsAndExposesTheCommittedCursor() throws Exception {
        jdbc.update("""
                INSERT INTO post_projection_cursor(post_id, last_event_id, updated_at)
                VALUES (42, 0, NOW(3))
                """);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch attemptingLock = new CountDownLatch(1);
        try (SqlSession first = sqlSessionFactory.openSession(false)) {
            PostProjectionReceiptMapper firstMapper = first.getMapper(PostProjectionReceiptMapper.class);
            assertThat(firstMapper.lockCursor(42L)).isZero();

            Future<Long> secondResult = executor.submit(() -> {
                try (SqlSession second = sqlSessionFactory.openSession(false)) {
                    attemptingLock.countDown();
                    Long value = second.getMapper(PostProjectionReceiptMapper.class).lockCursor(42L);
                    second.rollback();
                    return value;
                }
            });
            assertThat(attemptingLock.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> secondResult.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            assertThat(firstMapper.advanceCursor(42L, 0L, 101L)).isEqualTo(1);
            first.commit();

            assertThat(secondResult.get(5, TimeUnit.SECONDS)).isEqualTo(101L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void receiptRecordsItsPostAndCursorAdvanceUsesCompareAndSet() {
        jdbc.update("""
                INSERT INTO outbox(id, aggregate_type, aggregate_id, type, payload)
                VALUES (101, 'post', 42, 'PostPublished', JSON_OBJECT('id', 42))
                """);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            PostProjectionReceiptMapper mapper = session.getMapper(PostProjectionReceiptMapper.class);
            assertThat(mapper.insertCursorIfAbsent(42L)).isEqualTo(1);
            assertThat(mapper.insertReceipt(101L, 42L)).isEqualTo(1);
            assertThat(mapper.advanceCursor(42L, 0L, 101L)).isEqualTo(1);
            assertThat(mapper.advanceCursor(42L, 0L, 102L)).isZero();
        }

        assertThat(jdbc.queryForObject(
                "SELECT post_id FROM post_projection_receipt WHERE event_id=101", Long.class))
                .isEqualTo(42L);
    }

    @Test
    void outboxParentExistenceReflectsCleanupForDelayedDuplicateDetection() {
        jdbc.update("""
                INSERT INTO outbox(id, aggregate_type, aggregate_id, type, payload)
                VALUES (101, 'post', 42, 'PostPublished', JSON_OBJECT('id', 42))
                """);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            PostProjectionReceiptMapper mapper = session.getMapper(PostProjectionReceiptMapper.class);
            assertThat(mapper.countOutboxEvent(101L)).isEqualTo(1);
        }
        jdbc.update("DELETE FROM outbox WHERE id = 101");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            PostProjectionReceiptMapper mapper = session.getMapper(PostProjectionReceiptMapper.class);
            assertThat(mapper.countOutboxEvent(101L)).isZero();
        }
    }
}
