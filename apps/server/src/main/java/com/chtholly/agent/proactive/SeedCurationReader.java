package com.chtholly.agent.proactive;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import com.chtholly.seed.SeedCuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 读取 SeedContentAuditor 写入 Redis 的最新周刊策展。
 */
@Component
@ConditionalOnExpression("${agent.extensions.proactive.enabled:true} && ${agent.extensions.experience.enabled:true} && ${agent.extensions.community-actions.enabled:true}")
public class SeedCurationReader {

    private static final String CURATION_KEY = "agent:curation:latest";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public SeedCurationReader(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public SeedCuration getLatest() {
        String raw = redis.opsForValue().get(CURATION_KEY);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, SeedCuration.class);
        } catch (Exception e) {
            return null;
        }
    }
}
