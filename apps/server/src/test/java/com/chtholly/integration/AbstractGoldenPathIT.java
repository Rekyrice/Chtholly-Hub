package com.chtholly.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.util.stream.Stream;

/**
 * Shared real-infrastructure fixture for golden-path integration tests.
 */
@SpringBootTest
@ActiveProfiles("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractGoldenPathIT {

    private static final Logger log = LoggerFactory.getLogger(AbstractGoldenPathIT.class);

    private static final Network NETWORK = Network.newNetwork();

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("chtholly")
            .withUsername("chtholly")
            .withPassword("chtholly")
            .withCommand("--log-bin-trust-function-creators=1")
            .withNetwork(NETWORK);

    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis");

    protected static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
            .withNetwork(NETWORK);

    protected static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.11.0"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node")
            .withNetwork(NETWORK)
            .withNetworkAliases("elasticsearch");

    protected static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.9.0"))
            .withNetwork(NETWORK);

    protected static final ToxiproxyContainer.ContainerProxy REDIS_PROXY;
    protected static final ToxiproxyContainer.ContainerProxy ELASTICSEARCH_PROXY;

    static {
        Startables.deepStart(Stream.of(MYSQL, REDIS, KAFKA, ELASTICSEARCH, TOXIPROXY)).join();
        REDIS_PROXY = TOXIPROXY.getProxy(REDIS, 6379);
        ELASTICSEARCH_PROXY = TOXIPROXY.getProxy(ELASTICSEARCH, 9200);
        Runtime.getRuntime().addShutdownHook(new Thread(AbstractGoldenPathIT::stopContainers,
                "golden-path-containers-shutdown"));
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected ObjectMapper objectMapper;

    @DynamicPropertySource
    static void registerInfrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS_PROXY::getContainerIpAddress);
        registry.add("spring.data.redis.port", REDIS_PROXY::getProxyPort);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.elasticsearch.uris", () -> "http://"
                + ELASTICSEARCH_PROXY.getContainerIpAddress() + ":" + ELASTICSEARCH_PROXY.getProxyPort());
    }

    @AfterEach
    void restoreProxies() throws Exception {
        REDIS_PROXY.setConnectionCut(false);
        ELASTICSEARCH_PROXY.setConnectionCut(false);
    }

    protected void cleanRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    protected void cleanDatabase() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{"outbox", "follower", "following", "posts", "users"}) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    protected String canalEnvelope(long eventId, String payload) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", eventId);
        row.put("payload", payload);
        ArrayNode data = objectMapper.createArrayNode().add(row);
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("table", "outbox");
        envelope.put("type", "INSERT");
        envelope.set("data", data);
        return envelope.toString();
    }

    private static void stopContainers() {
        Stream.of(TOXIPROXY, ELASTICSEARCH, KAFKA, REDIS, MYSQL).forEach(container -> {
            try {
                container.stop();
            } catch (RuntimeException e) {
                log.warn("Failed to stop integration-test container {} during JVM shutdown: {}",
                        container.getDockerImageName(), e.getMessage());
            }
        });
        NETWORK.close();
    }
}
