package com.chtholly.health;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ListObjectsRequest;
import com.chtholly.storage.config.OssProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 阿里云 OSS 连通性探测（listObjects maxKeys=1）。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.type", havingValue = "oss")
public class OssHealthIndicator implements HealthIndicator {

    private final OssProperties props;

    @Override
    public Health health() {
        return HealthCheckSupport.runWithTimeout(this::probeOss);
    }

    private Health probeOss() {
        if (!isConfigured()) {
            return Health.down().withDetail("error", "对象存储未配置").build();
        }
        OSS client = new OSSClientBuilder().build(
                props.getEndpoint(), props.getAccessKeyId(), props.getAccessKeySecret());
        try {
            ListObjectsRequest request = new ListObjectsRequest(props.getBucket());
            request.setMaxKeys(1);
            client.listObjects(request);
            return Health.up().withDetail("bucket", props.getBucket()).build();
        } catch (Exception e) {
            return Health.down().withException(e).build();
        } finally {
            client.shutdown();
        }
    }

    private boolean isConfigured() {
        return StringUtils.hasText(props.getEndpoint())
                && StringUtils.hasText(props.getAccessKeyId())
                && StringUtils.hasText(props.getAccessKeySecret())
                && StringUtils.hasText(props.getBucket());
    }
}
