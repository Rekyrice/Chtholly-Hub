package com.chtholly.evidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionEventExperiment {
    private TransactionEventExperiment() {
    }

    public static void main(String[] args) throws IOException {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            TransactionTemplate transactions = context.getBean(TransactionTemplate.class);
            ApplicationEventPublisher publisher = context;
            ProbeListeners listeners = context.getBean(ProbeListeners.class);

            jdbc.execute("create table probe_item (id integer primary key, note varchar(80))");

            transactions.executeWithoutResult(status -> {
                jdbc.update("insert into probe_item(id, note) values (?, ?)", 1, "committed row");
                publisher.publishEvent(new ProbeEvent("commit", 1));
            });

            transactions.executeWithoutResult(status -> {
                jdbc.update("insert into probe_item(id, note) values (?, ?)", 2, "rolled back row");
                publisher.publishEvent(new ProbeEvent("rollback", 2));
                status.setRollbackOnly();
            });

            List<Observation> commitEvents = listeners.forScenario("commit");
            List<Observation> rollbackEvents = listeners.forScenario("rollback");
            requireExactPhases(commitEvents, "ordinary", "before_commit", "after_commit", "after_completion");
            requireExactPhases(rollbackEvents, "ordinary", "after_rollback", "after_completion");
            requireAbsent(rollbackEvents, "after_commit");
            requireVisibility(commitEvents, "ordinary", 0);
            requireVisibility(commitEvents, "before_commit", 0);
            requireVisibility(commitEvents, "after_commit", 1);
            requireVisibility(commitEvents, "after_completion", 1);
            requireVisibility(rollbackEvents, "ordinary", 0);
            requireVisibility(rollbackEvents, "after_rollback", 0);
            requireVisibility(rollbackEvents, "after_completion", 0);

            Path output = Path.of(System.getProperty("experiment.dir"), "results.json");
            Files.writeString(output, toJson(listeners.all()), StandardCharsets.UTF_8);
            System.out.println("wrote " + output.toAbsolutePath());
        }
    }

    private static void requireExactPhases(List<Observation> observations, String... expected) {
        List<String> actual = observations.stream().map(Observation::phase).toList();
        List<String> wanted = List.of(expected);
        if (!actual.equals(wanted)) {
            throw new IllegalStateException("expected phases " + wanted + " but got " + actual);
        }
    }

    private static void requireAbsent(List<Observation> observations, String phase) {
        if (observations.stream().anyMatch(item -> item.phase().equals(phase))) {
            throw new IllegalStateException("unexpected phase " + phase + " in " + observations);
        }
    }

    private static void requireVisibility(List<Observation> observations, String phase, int expected) {
        Observation found = observations.stream()
                .filter(item -> item.phase().equals(phase))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing phase " + phase));
        if (found.independentConnectionRowCount() != expected) {
            throw new IllegalStateException(
                    phase + " expected independent row count " + expected + " but got "
                            + found.independentConnectionRowCount());
        }
    }

    private static String toJson(List<Observation> observations) {
        String rows = observations.stream().map(item -> """
                {"sequence":%d,"scenario":"%s","listener":"%s","phase":"%s","rowId":%d,"independentConnectionRowCount":%d}
                """.formatted(
                        item.sequence(), item.scenario(), item.listener(), item.phase(), item.rowId(),
                        item.independentConnectionRowCount()).trim()).collect(Collectors.joining(",\n    "));
        return """
                {
                  "runtime": {"java":"21","springFramework":"6.1.6","h2":"2.2.224"},
                  "database": {"isolation":"READ_COMMITTED","visibilityProbe":"new raw JDBC connection"},
                  "observations": [
                    %s
                  ]
                }
                """.formatted(rows);
    }

    record ProbeEvent(String scenario, int rowId) {
    }

    record Observation(
            int sequence,
            String scenario,
            String listener,
            String phase,
            int rowId,
            int independentConnectionRowCount) {
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:transaction_events;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        ProbeListeners probeListeners(DataSource dataSource) {
            return new ProbeListeners(dataSource);
        }
    }

    static final class ProbeListeners {
        private final DataSource dataSource;
        private final AtomicInteger sequence = new AtomicInteger();
        private final List<Observation> observations = new ArrayList<>();

        ProbeListeners(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @EventListener
        @Order(0)
        public void ordinary(ProbeEvent event) {
            record("ordinary", event);
        }

        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        @Order(10)
        public void beforeCommit(ProbeEvent event) {
            record("before_commit", event);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        @Order(20)
        public void afterCommit(ProbeEvent event) {
            record("after_commit", event);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
        @Order(20)
        public void afterRollback(ProbeEvent event) {
            record("after_rollback", event);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)
        @Order(30)
        public void afterCompletion(ProbeEvent event) {
            record("after_completion", event);
        }

        private void record(String phase, ProbeEvent event) {
            observations.add(new Observation(
                    sequence.incrementAndGet(), event.scenario(), "ProbeListeners", phase, event.rowId(),
                    independentRowCount(event.rowId())));
        }

        private int independentRowCount(int rowId) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "select count(*) from probe_item where id = ?")) {
                statement.setInt(1, rowId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("independent visibility probe failed", exception);
            }
        }

        List<Observation> forScenario(String scenario) {
            return observations.stream().filter(item -> item.scenario().equals(scenario)).toList();
        }

        List<Observation> all() {
            return List.copyOf(observations);
        }
    }
}
