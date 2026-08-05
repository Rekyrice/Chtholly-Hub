package com.chtholly.integration;

import com.chtholly.auth.token.RefreshSessionEpochAuthority;
import com.chtholly.user.mapper.UserMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the user epoch migration and atomic MySQL mapper commands. */
@Testcontainers(disabledWithoutDocker = true)
class UserRefreshSessionEpochMapperIT {

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("user_epoch")
                    .withUsername("user_epoch")
                    .withPassword("user_epoch");

    private static MysqlDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static SqlSessionFactory sqlSessionFactory;
    private static AnnotationConfigApplicationContext springContext;
    private static RefreshSessionEpochAuthority epochAuthority;
    private static TransactionTemplate transactions;
    private static boolean preMigrationColumnWasAbsent;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        dataSource = new MysqlDataSource();
        dataSource.setURL(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        createPreV29UsersTable();
        jdbc.update("""
                INSERT INTO users
                    (id, email, password_hash, nickname)
                VALUES (29, 'legacy@example.com', 'legacy-hash', 'Legacy')
                """);
        preMigrationColumnWasAbsent = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'users'
                  AND column_name = 'refresh_session_epoch'
                """, Integer.class) == 0;
        try (Connection connection = dataSource.getConnection()) {
            executeMigration(connection);
            executeMigration(connection);
        }

        sqlSessionFactory = mapperFactory(new JdbcTransactionFactory());

        SqlSessionFactory springFactory =
                mapperFactory(new SpringManagedTransactionFactory());
        SqlSessionTemplate sqlSessionTemplate =
                new SqlSessionTemplate(springFactory);
        springContext = new AnnotationConfigApplicationContext();
        springContext.register(TransactionConfiguration.class);
        springContext.registerBean(DataSource.class, () -> dataSource);
        springContext.registerBean(
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        springContext.registerBean(
                UserMapper.class,
                () -> sqlSessionTemplate.getMapper(UserMapper.class));
        springContext.registerBean(RefreshSessionEpochAuthority.class);
        springContext.refresh();
        epochAuthority = springContext.getBean(
                RefreshSessionEpochAuthority.class);
        transactions = new TransactionTemplate(springContext.getBean(
                PlatformTransactionManager.class));
    }

    @AfterAll
    static void closeSpringContext() {
        if (springContext != null) {
            springContext.close();
        }
    }

    @BeforeEach
    void resetUser() {
        jdbc.update("DELETE FROM users WHERE id = 7");
        jdbc.update("""
                INSERT INTO users
                    (id, email, password_hash, nickname)
                VALUES (7, 'owner@example.com', 'old-hash', 'Owner')
                """);
    }

    @Test
    void migrationAddsEpochToPopulatedPreV29TableAndIsIdempotent() {
        assertThat(preMigrationColumnWasAbsent).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COLUMN_TYPE
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'users'
                  AND column_name = 'refresh_session_epoch'
                """, String.class)).isEqualTo("bigint");
        assertThat(jdbc.queryForObject("""
                SELECT COLUMN_DEFAULT
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'users'
                  AND column_name = 'refresh_session_epoch'
                """, String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject(
                "SELECT refresh_session_epoch FROM users WHERE id=29",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void passwordAndEpochChangeInOneMysqlStatement() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);

            assertThat(mapper.updatePasswordAndAdvanceRefreshSessionEpoch(
                    7L, "new-hash")).isEqualTo(1);
        }

        assertThat(jdbc.queryForMap("""
                SELECT password_hash, refresh_session_epoch
                FROM users WHERE id=7
                """))
                .containsEntry("password_hash", "new-hash")
                .containsEntry("refresh_session_epoch", 2L);
    }

    @Test
    void concurrentAdvancesDoNotLoseUpdates() throws Exception {
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "epoch advance barrier timed out");
                    }
                    try (SqlSession session =
                                 sqlSessionFactory.openSession(true)) {
                        return session.getMapper(UserMapper.class)
                                .advanceRefreshSessionEpoch(7L);
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "SELECT refresh_session_epoch FROM users WHERE id=7",
                Long.class)).isEqualTo(9L);
    }

    @Test
    void requiredAdvanceRollsBackWithTheOuterMysqlTransaction() {
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(
                epochAuthority)).isTrue();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                transactions.executeWithoutResult(ignored -> {
                    epochAuthority.advance(7L);
                    throw new IllegalStateException("rollback");
                })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
                "SELECT refresh_session_epoch FROM users WHERE id=7",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void requiresNewCurrentEscapesAnOuterRepeatableReadSnapshot()
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            transactions.executeWithoutResult(ignored -> {
                assertThat(jdbc.queryForObject(
                        "SELECT refresh_session_epoch FROM users WHERE id=7",
                        Long.class)).isEqualTo(1L);
                Future<?> advance = executor.submit(() ->
                        epochAuthority.advance(7L));
                try {
                    advance.get(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }

                assertThat(epochAuthority.current(7L)).isEqualTo(2L);
                assertThat(jdbc.queryForObject(
                        "SELECT refresh_session_epoch FROM users WHERE id=7",
                        Long.class)).isEqualTo(1L);
            });
        } finally {
            executor.shutdownNow();
        }
    }

    private static void executeMigration(Connection connection) {
        ScriptUtils.executeSqlScript(
                connection,
                new FileSystemResource(
                        "db/migration/V29__user_refresh_session_epoch.sql"));
    }

    private static void createPreV29UsersTable() {
        jdbc.execute("""
                CREATE TABLE users (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                    phone VARCHAR(32) NULL,
                    email VARCHAR(128) NULL,
                    password_hash VARCHAR(128) NULL,
                    nickname VARCHAR(64) NOT NULL,
                    avatar TEXT NULL,
                    bio VARCHAR(512) NULL,
                    handle VARCHAR(64) NULL,
                    gender VARCHAR(16) NULL,
                    birthday DATE NULL,
                    school VARCHAR(128) NULL,
                    tags_json JSON NULL,
                    role VARCHAR(20) NOT NULL DEFAULT 'USER',
                    banned_at DATETIME NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                        ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_users_phone (phone),
                    UNIQUE KEY uk_users_email (email),
                    UNIQUE KEY uk_users_handle (handle)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                  COLLATE=utf8mb4_unicode_ci
                """);
    }

    private static SqlSessionFactory mapperFactory(
            TransactionFactory transactionFactory) throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "user-epoch-it", transactionFactory, dataSource));
        configuration.addMapper(UserMapper.class);
        try (InputStream input = UserRefreshSessionEpochMapperIT.class
                .getResourceAsStream("/mapper/UserMapper.xml")) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(
                    input,
                    configuration,
                    "mapper/UserMapper.xml",
                    configuration.getSqlFragments())
                    .parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }
}
