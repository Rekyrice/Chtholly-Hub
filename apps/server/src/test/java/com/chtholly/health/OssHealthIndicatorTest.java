package com.chtholly.health;

import com.chtholly.storage.config.OssProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class OssHealthIndicatorTest {

    private OssProperties props;
    private OssHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        props = new OssProperties();
        indicator = new OssHealthIndicator(props);
    }

    @Test
    void health_down_whenNotConfigured() {
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "对象存储未配置");
    }

    @Test
    void health_down_whenOssUnreachable() {
        props.setEndpoint("oss-cn-beijing.aliyuncs.com");
        props.setAccessKeyId("invalid-key");
        props.setAccessKeySecret("invalid-secret");
        props.setBucket("nonexistent-bucket-xyz");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }
}
