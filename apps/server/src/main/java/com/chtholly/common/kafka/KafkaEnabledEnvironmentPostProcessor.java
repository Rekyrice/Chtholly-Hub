package com.chtholly.common.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * kafka.enabled=false 时排除 Kafka 自动配置，避免应用启动连接 Kafka。
 */
@Slf4j
public class KafkaEnabledEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String KAFKA_ENABLED = "kafka.enabled";
    private static final String AUTOCONFIGURE_EXCLUDE = "spring.autoconfigure.exclude";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (Boolean.parseBoolean(environment.getProperty(KAFKA_ENABLED, "false"))) {
            return;
        }
        String excludeClass = KafkaAutoConfiguration.class.getName();
        String existing = environment.getProperty(AUTOCONFIGURE_EXCLUDE, "");
        if (existing.contains(excludeClass)) {
            return;
        }
        String merged = existing.isBlank() ? excludeClass : existing + "," + excludeClass;
        Map<String, Object> props = new HashMap<>();
        props.put(AUTOCONFIGURE_EXCLUDE, merged);
        environment.getPropertySources().addFirst(new MapPropertySource("kafkaDisabledExcludes", props));
        log.info("Kafka 已禁用 (kafka.enabled=false)，跳过 KafkaAutoConfiguration");
    }
}
