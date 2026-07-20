package com.chtholly.integration;

import com.chtholly.relation.service.RelationService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Real MySQL binlog to Canal, Kafka and relation-consumer smoke test. */
class CanalRelationSmokeIT extends AbstractGoldenPathIT {

    private static final long FROM_USER_ID = 401L;
    private static final long TO_USER_ID = 402L;
    private static final int CANAL_PORT = 11111;
    private static final GenericContainer<?> CANAL;
    private static final ToxiproxyContainer.ContainerProxy CANAL_PROXY;

    static {
        createCanalReplicationUser();
        CANAL = new GenericContainer<>(DockerImageName.parse("canal/canal-server:v1.1.8"))
                .withNetwork(NETWORK)
                .withNetworkAliases("canal")
                .withExposedPorts(CANAL_PORT)
                .withEnv("canal.auto.scan", "false")
                .withEnv("canal.destinations", "example")
                .withEnv("canal.instance.master.address", "mysql:3306")
                .withEnv("canal.instance.dbUsername", "canal")
                .withEnv("canal.instance.dbPassword", "canal")
                .withEnv("canal.instance.connectionCharset", "UTF-8")
                .withEnv("canal.instance.tsdb.enable", "false")
                .withEnv("canal.instance.gtidon", "false")
                .withEnv("canal.instance.mysql.slaveId", "1234")
                .withEnv("canal.instance.parser.parallel", "false")
                .withEnv("canal.instance.filter.regex", "chtholly\\.outbox")
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(3));
        CANAL.start();
        CANAL_PROXY = TOXIPROXY.getProxy(CANAL, CANAL_PORT);
        Runtime.getRuntime().addShutdownHook(new Thread(CanalRelationSmokeIT::stopCanal,
                "canal-smoke-container-shutdown"));
    }

    @Autowired
    private RelationService relationService;

    @DynamicPropertySource
    static void registerCanalProperties(DynamicPropertyRegistry registry) {
        registry.add("canal.enabled", () -> true);
        registry.add("canal.host", CANAL_PROXY::getContainerIpAddress);
        registry.add("canal.port", CANAL_PROXY::getProxyPort);
        registry.add("canal.destination", () -> "example");
        registry.add("canal.username", () -> "canal");
        registry.add("canal.password", () -> "");
        registry.add("canal.filter", () -> "chtholly\\.outbox");
        registry.add("canal.batchSize", () -> 100);
        registry.add("canal.intervalMs", () -> 100L);
        registry.add("relation.calibration.enabled", () -> false);
    }

    @BeforeEach
    void setUpData() {
        cleanRedis();
        cleanDatabase();
        jdbc.update("INSERT INTO users (id, nickname, handle) VALUES (?, ?, ?)",
                FROM_USER_ID, "Canal Follower", "canal-follower");
        jdbc.update("INSERT INTO users (id, nickname, handle) VALUES (?, ?, ?)",
                TO_USER_ID, "Canal Target", "canal-target");
    }

    @AfterAll
    static void stopCanalAfterClass() {
        stopCanal();
    }

    @Test
    void followsAndUnfollowsThroughRealCanalPipeline() {
        assertThat(jdbc.queryForObject("SELECT @@binlog_format", String.class)).isEqualTo("ROW");
        assertThat(jdbc.queryForObject("SELECT @@binlog_row_image", String.class)).isEqualTo("FULL");

        String followingKey = "uf:flws:" + FROM_USER_ID;
        String followersKey = "uf:fans:" + TO_USER_ID;
        redis.opsForZSet().add(followingKey, "-1", 0D);
        redis.opsForZSet().add(followersKey, "-1", 0D);

        assertThat(relationService.follow(FROM_USER_ID, TO_USER_ID)).isTrue();
        long createdOutboxId = outboxId("FollowCreated");

        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(redis.hasKey("consumed:outbox:relation:" + createdOutboxId)).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM follower
                    WHERE from_user_id=? AND to_user_id=? AND rel_status=1
                    """, Long.class, FROM_USER_ID, TO_USER_ID)).isOne();
            assertThat(redis.opsForZSet().score(
                    followingKey, String.valueOf(TO_USER_ID))).isNotNull();
            assertThat(redis.opsForZSet().score(
                    followersKey, String.valueOf(FROM_USER_ID))).isNotNull();
        });
        redis.opsForZSet().remove(followingKey, "-1");
        redis.opsForZSet().remove(followersKey, "-1");
        assertThat(relationService.following(FROM_USER_ID, 10, 0)).containsExactly(TO_USER_ID);
        assertThat(relationService.followers(TO_USER_ID, 10, 0)).containsExactly(FROM_USER_ID);

        assertThat(relationService.unfollow(FROM_USER_ID, TO_USER_ID)).isTrue();
        long canceledOutboxId = outboxId("FollowCanceled");

        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(redis.hasKey("consumed:outbox:relation:" + canceledOutboxId)).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM follower
                    WHERE from_user_id=? AND to_user_id=? AND rel_status=1
                    """, Long.class, FROM_USER_ID, TO_USER_ID)).isZero();
            assertThat(redis.opsForZSet().score(
                    followingKey, String.valueOf(TO_USER_ID))).isNull();
            assertThat(redis.opsForZSet().score(
                    followersKey, String.valueOf(FROM_USER_ID))).isNull();
        });
    }

    @Test
    void replaysUnacknowledgedOutboxAfterCanalConnectionRecovers() throws Exception {
        CANAL_PROXY.setConnectionCut(true);
        long createdOutboxId;
        try {
            assertThat(relationService.follow(FROM_USER_ID, TO_USER_ID)).isTrue();
            createdOutboxId = outboxId("FollowCreated");
            Awaitility.await()
                    .during(Duration.ofSeconds(2))
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(
                            redis.hasKey("consumed:outbox:relation:" + createdOutboxId)).isFalse());
        } finally {
            CANAL_PROXY.setConnectionCut(false);
        }

        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(redis.hasKey("consumed:outbox:relation:" + createdOutboxId)).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM follower
                    WHERE from_user_id=? AND to_user_id=? AND rel_status=1
                    """, Long.class, FROM_USER_ID, TO_USER_ID)).isOne();
        });
    }

    private long outboxId(String type) {
        return jdbc.queryForObject("""
                SELECT id FROM outbox
                WHERE aggregate_type='following' AND type=?
                ORDER BY created_at DESC LIMIT 1
                """, Long.class, type);
    }

    private static void createCanalReplicationUser() {
        String rootPassword = MYSQL.getEnvMap().get("MYSQL_ROOT_PASSWORD");
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", rootPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY 'canal'");
            statement.execute("ALTER USER 'canal'@'%' IDENTIFIED BY 'canal'");
            statement.execute("GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%'");
            statement.execute("FLUSH PRIVILEGES");
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void stopCanal() {
        if (CANAL != null && CANAL.isRunning()) {
            CANAL.stop();
        }
    }
}
