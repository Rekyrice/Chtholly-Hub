package com.chtholly.seed;

import com.chtholly.agent.quality.QualityCriteria;
import com.chtholly.agent.quality.QualityEvaluationService;
import com.chtholly.agent.quality.QualityResult;
import com.chtholly.common.scheduler.DistributedLockService;
import com.chtholly.post.api.dto.PostSummary;
import com.chtholly.post.model.Post;
import com.chtholly.post.service.PostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeedContentAuditorTest {

    private static final Instant NOW = Instant.parse("2026-07-09T03:00:00Z");

    private PostService postService;
    private SeedMapper seedMapper;
    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashOps;
    private ValueOperations<String, String> valueOps;
    private DistributedLockService lockService;
    private ObjectMapper objectMapper;
    private QualityEvaluationService qualityEvaluationService;

    @BeforeEach
    void setUp() {
        postService = mock(PostService.class);
        seedMapper = mock(SeedMapper.class);
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        valueOps = mock(ValueOperations.class);
        lockService = mock(DistributedLockService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        qualityEvaluationService = mock(QualityEvaluationService.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(qualityEvaluationService.evaluate(any(), any(), any())).thenReturn(
                new QualityResult(2.4, "像提纲，不像一篇完整文章。", false, Map.of("内容深度", 2.0)));
    }

    @Test
    void dailyAuditStoresNeedsReviewResultForLowQualitySeedPost() {
        when(lockService.tryLock("lock:scheduled:seedAudit", Duration.ofMinutes(15))).thenReturn(true);
        when(postService.getRecentSeedPosts(Duration.ofHours(24))).thenReturn(List.of(seedPost()));
        SeedContentAuditor auditor = auditor(prompt -> "");

        auditor.dailyAudit();

        verify(hashOps).put(eq("agent:audit:posts"), eq("42"), any(String.class));
        verify(qualityEvaluationService).evaluate(
                any(),
                org.mockito.ArgumentMatchers.contains("文章标题：夜里重构 CLI 的记录"),
                eq(QualityCriteria.articleQuality()));
        verify(redis).expire("agent:audit:posts", Duration.ofDays(30));
        verify(lockService).unlock("lock:scheduled:seedAudit");
    }

    @Test
    void dailyAuditUsesQualityServiceWhenTextGeneratorUnavailable() {
        when(lockService.tryLock("lock:scheduled:seedAudit", Duration.ofMinutes(15))).thenReturn(true);
        when(postService.getRecentSeedPosts(Duration.ofHours(24))).thenReturn(List.of(seedPost()));
        SeedContentAuditor auditor = auditor(new SeedContentAuditor.TextGenerator() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String generate(String prompt) {
                return "";
            }
        });

        auditor.dailyAudit();

        verify(hashOps).put(eq("agent:audit:posts"), eq("42"), any(String.class));
        verify(qualityEvaluationService).evaluate(any(), any(), eq(QualityCriteria.articleQuality()));
        verify(lockService).unlock("lock:scheduled:seedAudit");
    }

    @Test
    void weeklyCurationStoresLatestCollection() {
        when(lockService.tryLock("lock:scheduled:weeklyCuration", Duration.ofMinutes(30))).thenReturn(true);
        when(postService.getRecentPosts(Duration.ofDays(7))).thenReturn(List.of(
                new PostSummary(42L, "夜里重构 CLI 的记录", "关于 Rust 和小工具", NOW.minus(Duration.ofDays(1)))
        ));
        SeedContentAuditor auditor = auditor(prompt -> """
                {"note":"这周的文章都有一点认真生活的味道。","posts":[
                  {"postId":42,"title":"夜里重构 CLI 的记录","comment":"这篇把踩坑写得很诚实，适合慢慢看。"}
                ]}
                """);

        auditor.weeklyCuration();

        verify(valueOps).set(eq("agent:curation:latest"), any(String.class), eq(Duration.ofDays(30)));
        verify(lockService).unlock("lock:scheduled:weeklyCuration");
    }

    @Test
    void monitorSeedHealthStoresPerSeedAccountSnapshot() {
        when(lockService.tryLock("lock:scheduled:seedHealth", Duration.ofMinutes(10))).thenReturn(true);
        when(seedMapper.listSeedAccountHealthSince(NOW.minus(Duration.ofDays(7)))).thenReturn(List.of(
                new SeedAccountHealthRow(7L, "sakura", "Sakura", 2, 3)
        ));
        when(postService.getRecentSeedPosts(Duration.ofDays(7))).thenReturn(List.of(seedPost()));
        when(hashOps.entries("agent:audit:posts")).thenReturn(Map.of(
                "42", "{\"qualityScore\":4.2,\"feedback\":\"不错\",\"needsReview\":false,\"auditedAt\":\"2026-07-09T03:00:00Z\"}"
        ));
        SeedContentAuditor auditor = auditor(prompt -> "");

        auditor.monitorSeedHealth();

        verify(hashOps).put(eq("agent:seed:health"), eq("7"), any(String.class));
        verify(redis).expire("agent:seed:health", Duration.ofDays(30));
        verify(lockService).unlock("lock:scheduled:seedHealth");
    }

    private SeedContentAuditor auditor(SeedContentAuditor.TextGenerator textGenerator) {
        return new SeedContentAuditor(
                postService,
                seedMapper,
                redis,
                objectMapper,
                lockService,
                qualityEvaluationService,
                textGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC),
                post -> "这是一篇种子文章正文，内容还算完整。");
    }

    private static Post seedPost() {
        return Post.builder()
                .id(42L)
                .creatorId(7L)
                .title("夜里重构 CLI 的记录")
                .description("关于 Rust 和小工具")
                .contentObjectKey("seed/posts/sakura-1.md")
                .publishTime(NOW.minus(Duration.ofHours(2)))
                .build();
    }
}
