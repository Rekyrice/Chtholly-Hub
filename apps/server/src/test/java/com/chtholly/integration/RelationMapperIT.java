package com.chtholly.integration;

import com.chtholly.relation.mapper.RelationMapper;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies relation transition and keyset SQL against MySQL affected-row semantics. */
@Testcontainers(disabledWithoutDocker = true)
class RelationMapperIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("relation_mapper")
            .withUsername("relation")
            .withPassword("relation");

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
                CREATE TABLE following (
                    id BIGINT UNSIGNED NOT NULL,
                    from_user_id BIGINT UNSIGNED NOT NULL,
                    to_user_id BIGINT UNSIGNED NOT NULL,
                    rel_status TINYINT NOT NULL DEFAULT 1,
                    created_at DATETIME(3) NOT NULL,
                    updated_at DATETIME(3) NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_from_to (from_user_id, to_user_id),
                    KEY idx_from_created (from_user_id, created_at, to_user_id, rel_status)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE follower (
                    id BIGINT UNSIGNED NOT NULL,
                    to_user_id BIGINT UNSIGNED NOT NULL,
                    from_user_id BIGINT UNSIGNED NOT NULL,
                    rel_status TINYINT NOT NULL DEFAULT 1,
                    created_at DATETIME(3) NOT NULL,
                    updated_at DATETIME(3) NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_to_from (to_user_id, from_user_id),
                    KEY idx_to_created (to_user_id, created_at, from_user_id, rel_status)
                ) ENGINE=InnoDB
                """);

        Configuration configuration = new Configuration(new Environment(
                "relation-mapper-it", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(RelationMapper.class);
        try (InputStream input = RelationMapperIT.class.getResourceAsStream(
                "/mapper/RelationMapper.xml")) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(
                    input,
                    configuration,
                    "mapper/RelationMapper.xml",
                    configuration.getSqlFragments())
                    .parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void clearRows() {
        jdbc.execute("TRUNCATE TABLE following");
        jdbc.execute("TRUNCATE TABLE follower");
    }

    @Test
    void duplicateActiveInsertAndRepeatedCancelAreNoOpTransitions() {
        Timestamp fixed = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            RelationMapper mapper = session.getMapper(RelationMapper.class);
            assertThat(mapper.insertFollowing(101L, 11L, 22L, 1)).isEqualTo(1);
            jdbc.update(
                    "UPDATE following SET created_at=?, updated_at=? WHERE id=101",
                    fixed,
                    fixed);

            assertThat(mapper.insertFollowing(202L, 11L, 22L, 1)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT id FROM following WHERE from_user_id=11 AND to_user_id=22",
                    Long.class)).isEqualTo(101L);
            assertThat(jdbc.queryForObject(
                    "SELECT updated_at FROM following WHERE id=101",
                    Timestamp.class)).isEqualTo(fixed);

            assertThat(mapper.cancelFollowing(11L, 22L)).isEqualTo(1);
            assertThat(mapper.cancelFollowing(11L, 22L)).isZero();
        }
    }

    @Test
    void inactiveRowCanBeActivatedOnceWithoutReplacingItsAuthorityId() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            RelationMapper mapper = session.getMapper(RelationMapper.class);
            mapper.insertFollowing(101L, 11L, 22L, 1);
            mapper.cancelFollowing(11L, 22L);

            assertThat(mapper.activateFollowing(11L, 22L)).isEqualTo(1);
            assertThat(mapper.activateFollowing(11L, 22L)).isEqualTo(0);
            assertThat(jdbc.queryForObject(
                    "SELECT id FROM following WHERE from_user_id=11 AND to_user_id=22",
                    Long.class)).isEqualTo(101L);
        }
    }

    @Test
    void concurrentInactiveActivationHasExactlyOneWinner() throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            RelationMapper mapper = session.getMapper(RelationMapper.class);
            mapper.insertFollowing(101L, 11L, 22L, 1);
            mapper.cancelFollowing(11L, 22L);
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() ->
                    activateAfterBarrier(ready, start));
            Future<Integer> second = executor.submit(() ->
                    activateAfterBarrier(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(1, 0);
            assertThat(jdbc.queryForObject(
                    "SELECT id FROM following WHERE from_user_id=11 AND to_user_id=22",
                    Long.class)).isEqualTo(101L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void compositeCursorStrictlyOrdersRowsSharingTheSameMillisecond() {
        Timestamp tied = Timestamp.from(Instant.parse("2026-01-02T00:00:00Z"));
        Timestamp older = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        insertRow(101L, 11L, 20L, tied);
        insertRow(102L, 11L, 30L, tied);
        insertRow(103L, 11L, 40L, tied);
        insertRow(104L, 11L, 50L, older);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            RelationMapper mapper = session.getMapper(RelationMapper.class);
            List<RelationMapper.RelationPageRow> first =
                    mapper.listFollowingPage(11L, null, null, 2);
            List<RelationMapper.RelationPageRow> second =
                    mapper.listFollowingPage(11L, tied, 30L, 2);

            assertThat(first)
                    .extracting(RelationMapper.RelationPageRow::relatedUserId)
                    .containsExactly(40L, 30L);
            assertThat(second)
                    .extracting(RelationMapper.RelationPageRow::relatedUserId)
                    .containsExactly(20L, 50L);
        }
    }

    @Test
    void followerCursorUsesTheSameStrictCompositeOrder() {
        Timestamp tied = Timestamp.from(Instant.parse("2026-01-02T00:00:00Z"));
        Timestamp older = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        insertFollowerRow(201L, 11L, 20L, tied);
        insertFollowerRow(202L, 11L, 30L, tied);
        insertFollowerRow(203L, 11L, 40L, tied);
        insertFollowerRow(204L, 11L, 50L, older);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            RelationMapper mapper = session.getMapper(RelationMapper.class);
            List<RelationMapper.RelationPageRow> first =
                    mapper.listFollowerPage(11L, null, null, 2);
            List<RelationMapper.RelationPageRow> second =
                    mapper.listFollowerPage(11L, tied, 30L, 2);

            assertThat(first)
                    .extracting(RelationMapper.RelationPageRow::relatedUserId)
                    .containsExactly(40L, 30L);
            assertThat(second)
                    .extracting(RelationMapper.RelationPageRow::relatedUserId)
                    .containsExactly(20L, 50L);
        }
    }

    private static void insertRow(
            long id,
            long fromUserId,
            long toUserId,
            Timestamp createdAt) {
        jdbc.update("""
                        INSERT INTO following
                            (id, from_user_id, to_user_id, rel_status, created_at, updated_at)
                        VALUES (?, ?, ?, 1, ?, ?)
                        """,
                id,
                fromUserId,
                toUserId,
                createdAt,
                createdAt);
    }

    private static void insertFollowerRow(
            long id,
            long toUserId,
            long fromUserId,
            Timestamp createdAt) {
        jdbc.update("""
                        INSERT INTO follower
                            (id, to_user_id, from_user_id, rel_status, created_at, updated_at)
                        VALUES (?, ?, ?, 1, ?, ?)
                        """,
                id,
                toUserId,
                fromUserId,
                createdAt,
                createdAt);
    }

    private static int activateAfterBarrier(
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("activation barrier timed out");
        }
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(RelationMapper.class)
                    .activateFollowing(11L, 22L);
        }
    }
}
