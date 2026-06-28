package com.chtholly.counter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 计数模块调度配置（与 Kafka 开关无关）。 */
@Configuration
@EnableScheduling
public class CounterSchedulingConfig {
}
