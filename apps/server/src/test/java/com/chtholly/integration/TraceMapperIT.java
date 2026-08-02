package com.chtholly.integration;

import com.chtholly.agent.trace.ExecutionTraceRow;
import com.chtholly.agent.trace.TraceMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes the failure-candidate mapper query against MySQL 8. */
@Testcontainers(disabledWithoutDocker = true)
class TraceMapperIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("trace_mapper")
            .withUsername("trace")
            .withPassword("trace");

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE execution_traces (
                        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        correlation_id VARCHAR(64) NOT NULL,
                        user_id BIGINT NULL,
                        session_id VARCHAR(128) NULL,
                        started_at DATETIME(3) NOT NULL,
                        finished_at DATETIME(3) NULL,
                        duration_ms INT NULL,
                        status VARCHAR(16) NOT NULL,
                        steps_count INT NOT NULL DEFAULT 0,
                        tool_calls JSON NULL,
                        error_message TEXT NULL,
                        input_tokens INT NOT NULL DEFAULT 0,
                        output_tokens INT NOT NULL DEFAULT 0,
                        trace_payload JSON NOT NULL,
                        pattern_analyzed TINYINT(1) NOT NULL DEFAULT 0,
                        created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    )
                    """);
        }

        Configuration configuration = new Configuration(new Environment(
                "trace-mapper-it", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(TraceMapper.class);
        try (InputStream input = TraceMapperIT.class.getResourceAsStream("/mapper/TraceMapper.xml")) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(
                    input,
                    configuration,
                    "mapper/TraceMapper.xml",
                    configuration.getSqlFragments())
                    .parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void findsAllUnanalyzedFailureCandidatesAndExcludesSuccessfulNone() throws Exception {
        insert("failure-none", "FAILURE", "{\"failureType\":\"NONE\"}", false, 1);
        insert("timeout-none", "TIMEOUT", "{\"failureType\":\"NONE\"}", false, 2);
        insert("aborted-legacy", "ABORTED", "{}", false, 3);
        insert("success-citation", "SUCCESS", "{\"failureType\":\"CITATION_INVALID\"}", false, 4);
        insert("success-none", "SUCCESS", "{\"failureType\":\"NONE\"}", false, 5);
        insert("analyzed-failure", "FAILURE", "{\"failureType\":\"NONE\"}", true, 6);

        try (var session = sqlSessionFactory.openSession()) {
            List<String> correlationIds = session.getMapper(TraceMapper.class)
                    .findUnanalyzedFailureCandidates(10)
                    .stream()
                    .map(ExecutionTraceRow::getCorrelationId)
                    .toList();

            assertThat(correlationIds).containsExactly(
                    "failure-none",
                    "timeout-none",
                    "aborted-legacy",
                    "success-citation");
        }
    }

    private void insert(
            String correlationId,
            String status,
            String tracePayload,
            boolean patternAnalyzed,
            long startedAtSecond) throws Exception {
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO execution_traces (
                         correlation_id, started_at, status, trace_payload, pattern_analyzed
                     ) VALUES (?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, correlationId);
            statement.setTimestamp(2, Timestamp.from(Instant.parse("2026-08-02T00:00:00Z")
                    .plusSeconds(startedAtSecond)));
            statement.setString(3, status);
            statement.setString(4, tracePayload);
            statement.setBoolean(5, patternAnalyzed);
            statement.executeUpdate();
        }
    }
}
