package com.chtholly.cache.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class CachePropertiesTest {

    @Test
    void fullModeIsTheSafeDefault() {
        CacheProperties properties = new CacheProperties();

        assertThat(properties.getReadMode()).isEqualTo(CacheProperties.ReadMode.FULL);
        assertThat(properties.getReadMode().usesCache()).isTrue();
        assertThat(properties.getReadMode().usesSingleFlight()).isTrue();
        assertThat(properties.getReadMode().externalName()).isEqualTo("full");
    }

    @Test
    void bindsAllThreeBenchmarkModes() {
        assertMode("db-only", CacheProperties.ReadMode.DB_ONLY, false, false);
        assertMode("full-no-singleflight", CacheProperties.ReadMode.FULL_NO_SINGLEFLIGHT, true, false);
        assertMode("full", CacheProperties.ReadMode.FULL, true, true);
    }

    @Test
    void applicationYamlBridgesBenchmarkEnvironmentVariable() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(CacheProperties.class)
                .withSystemProperties(
                        "spring.config.location=classpath:application.yml",
                        "CACHE_READ_MODE=full-no-singleflight")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CacheProperties.class).getReadMode())
                            .isEqualTo(CacheProperties.ReadMode.FULL_NO_SINGLEFLIGHT);
                });
    }

    @Test
    void invalidBenchmarkModeFailsApplicationStartup() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(CacheProperties.class)
                .withSystemProperties(
                        "spring.config.location=classpath:application.yml",
                        "CACHE_READ_MODE=redis-only")
                .run(context -> assertThat(context).hasFailed());
    }

    private void assertMode(
            String configured,
            CacheProperties.ReadMode expected,
            boolean usesCache,
            boolean usesSingleFlight
    ) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("cache.read-mode", configured);

        CacheProperties properties = Binder.get(environment)
                .bind("cache", CacheProperties.class)
                .orElseThrow(IllegalStateException::new);

        assertThat(properties.getReadMode()).isEqualTo(expected);
        assertThat(properties.getReadMode().usesCache()).isEqualTo(usesCache);
        assertThat(properties.getReadMode().usesSingleFlight()).isEqualTo(usesSingleFlight);
        assertThat(properties.getReadMode().externalName()).isEqualTo(configured);
    }
}
