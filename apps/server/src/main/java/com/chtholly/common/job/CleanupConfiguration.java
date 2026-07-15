package com.chtholly.common.job;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 数据清理定时任务与配置。 */
@Configuration
@ConditionalOnProperty(name = "seed.cli-read-only", havingValue = "false", matchIfMissing = true)
@EnableScheduling
@EnableConfigurationProperties(CleanupProperties.class)
public class CleanupConfiguration {
}
