package com.chtholly.seed;

import com.chtholly.agent.content.TopicCluster;
import com.chtholly.agent.content.TopicClusteringService;
import com.chtholly.agent.quality.QualityCriteria;
import com.chtholly.agent.quality.QualityEvaluationService;
import com.chtholly.agent.quality.QualityResult;
import com.chtholly.common.scheduler.DistributedLockService;
import com.chtholly.post.api.dto.PostSummary;
import com.chtholly.post.model.Post;
import com.chtholly.post.service.PostService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reviews seed content after publication and curates weekly collections.
 *
 * <p>Audit and curation results are persisted in Redis so the admin dashboard
 * and Hub discovery surfaces can consume them without coupling to scheduled jobs.
 */
@Slf4j
@Component
public class SeedContentAuditor {

    static final String AUDIT_KEY = "agent:audit:posts";
    static final String CURATION_KEY = "agent:curation:latest";
    static final String HEALTH_KEY = "agent:seed:health";

    private static final String AUDIT_LOCK = "lock:scheduled:seedAudit";
    private static final String CURATION_LOCK = "lock:scheduled:weeklyCuration";
    private static final String HEALTH_LOCK = "lock:scheduled:seedHealth";
    private static final Duration REDIS_TTL = Duration.ofDays(30);
    private static final int MAX_AUDIT_POSTS = 50;
    private static final int MAX_CONTENT_CHARS = 5000;

    private final PostService postService;
    private final SeedMapper seedMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final DistributedLockService lockService;
    private final QualityEvaluationService qualityEvaluationService;
    private final TextGenerator textGenerator;
    private final Clock clock;
    private final PostContentLoader contentLoader;
    private final TopicClusteringService topicClusteringService;

    @Autowired
    public SeedContentAuditor(PostService postService,
                              SeedMapper seedMapper,
                              StringRedisTemplate redis,
                              ObjectMapper objectMapper,
                              DistributedLockService lockService,
                              QualityEvaluationService qualityEvaluationService,
                              ObjectProvider<ChatClient> chatClientProvider,
                              ObjectProvider<TopicClusteringService> topicClusteringServiceProvider) {
        this(postService,
                seedMapper,
                redis,
                objectMapper,
                lockService,
                qualityEvaluationService,
                new ChatClientTextGenerator(chatClientProvider),
                Clock.systemUTC(),
                SeedContentAuditor::defaultLoadPostContent,
                topicClusteringServiceProvider.getIfAvailable());
    }

    SeedContentAuditor(PostService postService,
                       SeedMapper seedMapper,
                       StringRedisTemplate redis,
                       ObjectMapper objectMapper,
                       DistributedLockService lockService,
                       QualityEvaluationService qualityEvaluationService,
                       TextGenerator textGenerator,
                       Clock clock,
                       PostContentLoader contentLoader) {
        this(postService, seedMapper, redis, objectMapper, lockService, qualityEvaluationService,
                textGenerator, clock, contentLoader, null);
    }

    SeedContentAuditor(PostService postService,
                       SeedMapper seedMapper,
                       StringRedisTemplate redis,
                       ObjectMapper objectMapper,
                       DistributedLockService lockService,
                       QualityEvaluationService qualityEvaluationService,
                       TextGenerator textGenerator,
                       Clock clock,
                       PostContentLoader contentLoader,
                       TopicClusteringService topicClusteringService) {
        this.postService = postService;
        this.seedMapper = seedMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.lockService = lockService;
        this.qualityEvaluationService = qualityEvaluationService;
        this.textGenerator = textGenerator;
        this.clock = clock;
        this.contentLoader = contentLoader;
        this.topicClusteringService = topicClusteringService;
    }

    /**
     * Reviews seed posts from the last 24 hours.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void dailyAudit() {
        runLocked("seedAudit", AUDIT_LOCK, Duration.ofMinutes(15), this::auditRecentSeedPosts);
    }

    /**
     * Builds the latest weekly Chtholly curation.
     */
    @Scheduled(cron = "0 0 10 * * 1")
    public void weeklyCuration() {
        runLocked("weeklyCuration", CURATION_LOCK, Duration.ofMinutes(30), this::curateRecentPosts);
    }

    /**
     * Stores seed account activity health snapshots.
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void monitorSeedHealth() {
        runLocked("seedHealth", HEALTH_LOCK, Duration.ofMinutes(10), this::storeSeedHealth);
    }

    /**
     * Returns posts currently marked for admin review.
     *
     * @return seed audit results whose needsReview flag is true.
     */
    public List<SeedAuditResultResponse> listNeedsReviewResults() {
        Map<Object, Object> entries = redis.opsForHash().entries(AUDIT_KEY);
        return entries.entrySet().stream()
                .map(entry -> toAuditResponse(entry.getKey(), entry.getValue()))
                .flatMap(Optional::stream)
                .filter(SeedAuditResultResponse::needsReview)
                .sorted(Comparator.comparing(SeedAuditResultResponse::auditedAt).reversed())
                .toList();
    }

    private void auditRecentSeedPosts() {
        List<Post> posts = postService.getRecentSeedPosts(Duration.ofHours(24));
        for (Post post : posts.stream().limit(MAX_AUDIT_POSTS).toList()) {
            auditPost(post).ifPresent(result -> storeAuditResult(post.getId(), result));
        }
    }

    private Optional<SeedAuditResult> auditPost(Post post) {
        if (post == null || post.getId() == null) {
            return Optional.empty();
        }
        String content = safeContent(post);
        try {
            QualityResult quality = qualityEvaluationService.evaluate(
                    truncate(content, MAX_CONTENT_CHARS),
                    articleContext(post),
                    QualityCriteria.articleQuality());
            if (quality == null) {
                return Optional.empty();
            }
            return Optional.of(new SeedAuditResult(
                    quality.score(),
                    normalizeFeedback(quality.feedback()),
                    !quality.passed(),
                    clock.instant()));
        } catch (Exception e) {
            log.warn("Seed post audit failed, postId={}: {}", post.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private void storeAuditResult(Long postId, SeedAuditResult result) {
        if (postId == null || result == null) {
            return;
        }
        try {
            redis.opsForHash().put(AUDIT_KEY, String.valueOf(postId), objectMapper.writeValueAsString(result));
            redis.expire(AUDIT_KEY, REDIS_TTL);
        } catch (JsonProcessingException e) {
            log.warn("Serialize seed audit result failed, postId={}: {}", postId, e.getMessage());
        }
    }

    private void curateRecentPosts() {
        if (!textGenerator.available()) {
            log.debug("Weekly curation skipped because LLM is unavailable");
            return;
        }
        List<PostSummary> posts = postService.getRecentPosts(Duration.ofDays(7));
        if (posts.isEmpty()) {
            return;
        }
        String topicHint = formatTopicClusters();
        String prompt = """
                你是珂朵莉。这是本周社区发布的文章列表：
                %s

                %s
                请选出 3-5 篇最值得推荐的，并给每篇写一句推荐语。
                选的标准：内容有深度、有趣、或对读者有帮助；优先覆盖不同热门话题，避免同质化。
                另写一句本周合集导语。
                只输出 JSON：
                {
                  "note": "合集导语",
                  "posts": [
                    { "postId": 123, "title": "...", "comment": "这篇写得真好..." }
                  ]
                }
                """.formatted(formatPosts(posts), topicHint);
        try {
            String raw = textGenerator.generate(prompt);
            if (raw == null || raw.isBlank()) {
                return;
            }
            CurationDraft draft = objectMapper.readValue(extractJsonObject(raw), CurationDraft.class);
            List<CuratedPost> curatedPosts = draft.posts() == null
                    ? List.of()
                    : draft.posts().stream()
                    .filter(post -> post.postId() > 0)
                    .limit(5)
                    .map(post -> new CuratedPost(post.postId(), nullToBlank(post.title()), nullToBlank(post.comment())))
                    .toList();
            if (curatedPosts.isEmpty()) {
                return;
            }
            SeedCuration curation = new SeedCuration(
                    curatedPosts,
                    draft.note() == null || draft.note().isBlank() ? "这周也有几篇值得慢慢读的文章。" : draft.note().trim(),
                    clock.instant());
            redis.opsForValue().set(CURATION_KEY, objectMapper.writeValueAsString(curation), REDIS_TTL);
        } catch (Exception e) {
            log.warn("Weekly curation failed: {}", e.getMessage());
        }
    }

    private void storeSeedHealth() {
        Instant since = clock.instant().minus(Duration.ofDays(7));
        List<SeedAccountHealthRow> rows = seedMapper.listSeedAccountHealthSince(since);
        Map<Long, Double> averageScores = averageScoresByCreator();
        for (SeedAccountHealthRow row : rows) {
            SeedHealthSnapshot snapshot = new SeedHealthSnapshot(
                    row.userId(),
                    row.handle(),
                    row.nickname(),
                    row.posts7d(),
                    row.comments7d(),
                    averageScores.getOrDefault(row.userId(), 0.0),
                    clock.instant());
            try {
                redis.opsForHash().put(HEALTH_KEY, String.valueOf(row.userId()), objectMapper.writeValueAsString(snapshot));
            } catch (JsonProcessingException e) {
                log.warn("Serialize seed health snapshot failed, userId={}: {}", row.userId(), e.getMessage());
            }
        }
        redis.expire(HEALTH_KEY, REDIS_TTL);
    }

    private Map<Long, Double> averageScoresByCreator() {
        List<Post> recentSeedPosts = postService.getRecentSeedPosts(Duration.ofDays(7));
        Map<String, SeedAuditResult> audits = loadAuditResults();
        Map<Long, List<Double>> scores = new HashMap<>();
        for (Post post : recentSeedPosts) {
            if (post.getId() == null || post.getCreatorId() == null) {
                continue;
            }
            SeedAuditResult audit = audits.get(String.valueOf(post.getId()));
            if (audit != null) {
                scores.computeIfAbsent(post.getCreatorId(), ignored -> new ArrayList<>()).add(audit.qualityScore());
            }
        }
        Map<Long, Double> averages = new HashMap<>();
        scores.forEach((creatorId, values) -> averages.put(creatorId,
                values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0)));
        return averages;
    }

    private Map<String, SeedAuditResult> loadAuditResults() {
        Map<Object, Object> entries = redis.opsForHash().entries(AUDIT_KEY);
        Map<String, SeedAuditResult> results = new HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            try {
                results.put(String.valueOf(entry.getKey()),
                        objectMapper.readValue(String.valueOf(entry.getValue()), SeedAuditResult.class));
            } catch (Exception e) {
                log.debug("Skip invalid seed audit result, postId={}", entry.getKey());
            }
        }
        return results;
    }

    private Optional<SeedAuditResultResponse> toAuditResponse(Object key, Object value) {
        try {
            long postId = Long.parseLong(String.valueOf(key));
            SeedAuditResult result = objectMapper.readValue(String.valueOf(value), SeedAuditResult.class);
            return Optional.of(new SeedAuditResultResponse(
                    postId,
                    result.qualityScore(),
                    result.feedback(),
                    result.needsReview(),
                    result.auditedAt()));
        } catch (Exception e) {
            log.debug("Skip invalid seed audit response, postId={}", key);
            return Optional.empty();
        }
    }

    private void runLocked(String taskName, String lockKey, Duration ttl, Runnable task) {
        if (!lockService.tryLock(lockKey, ttl)) {
            log.debug("Scheduled seed task skipped because lock is held, task={}", taskName);
            return;
        }
        long startNanos = System.nanoTime();
        boolean success = false;
        try {
            task.run();
            success = true;
        } catch (Exception e) {
            log.error("Scheduled seed task failed, task={}", taskName, e);
        } finally {
            long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            lockService.recordRun(taskName, durationMs, success);
            lockService.unlock(lockKey);
        }
    }

    private String safeContent(Post post) {
        try {
            String content = contentLoader.load(post);
            if (content != null && !content.isBlank()) {
                return content;
            }
        } catch (Exception e) {
            log.debug("Load seed post content failed, postId={}: {}", post.getId(), e.getMessage());
        }
        return nullToBlank(post.getDescription());
    }

    private static String defaultLoadPostContent(Post post) {
        String url = post.getContentUrl();
        if (url == null || url.isBlank() || url.startsWith("seed://")) {
            return "";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "";
        }
        return new RestTemplate().getForObject(url, String.class);
    }

    private String formatTopicClusters() {
        if (topicClusteringService == null) {
            return "本周热门话题：暂无聚类数据。";
        }
        try {
            List<TopicCluster> clusters = topicClusteringService.getStoredClusters();
            if (clusters == null || clusters.isEmpty()) {
                return "本周热门话题：暂无聚类数据。";
            }
            String body = clusters.stream()
                    .limit(5)
                    .map(c -> "- %s（%d 篇）：%s；代表文章 ID=%s".formatted(
                            nullToBlank(c.topicName()),
                            c.size(),
                            nullToBlank(c.summary()),
                            c.postIds() == null ? "" : c.postIds().stream()
                                    .limit(5)
                                    .map(String::valueOf)
                                    .reduce((a, b) -> a + "," + b)
                                    .orElse("")))
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            return "本周热门话题（请尽量从不同话题中各选一些）：\n" + body;
        } catch (Exception e) {
            log.debug("Load topic clusters for curation failed: {}", e.getMessage());
            return "本周热门话题：暂无聚类数据。";
        }
    }

    private static String formatPosts(List<PostSummary> posts) {
        return posts.stream()
                .map(post -> "- id=%s 标题=%s 摘要=%s 发布时间=%s".formatted(
                        post.id(),
                        nullToBlank(post.title()),
                        nullToBlank(post.description()),
                        post.publishTime()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private static String articleContext(Post post) {
        return """
                你是珂朵莉，这个网站的常驻居民。
                请从内容深度、可读性、是否有价值、是否像真人写的角度审核文章。
                文章标题：%s
                文章摘要：%s
                """.formatted(nullToBlank(post.getTitle()), nullToBlank(post.getDescription()));
    }

    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return nullToBlank(text);
        }
        return text.substring(0, maxChars);
    }

    private static String normalizeFeedback(String feedback) {
        if (feedback == null || feedback.isBlank()) {
            return "这篇文章还需要再认真打磨一下。";
        }
        return feedback.trim();
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    @FunctionalInterface
    interface PostContentLoader {
        String load(Post post);
    }

    interface TextGenerator {
        default boolean available() {
            return true;
        }

        String generate(String prompt);
    }

    private static final class ChatClientTextGenerator implements TextGenerator {
        private final ObjectProvider<ChatClient> chatClientProvider;

        private ChatClientTextGenerator(ObjectProvider<ChatClient> chatClientProvider) {
            this.chatClientProvider = chatClientProvider;
        }

        @Override
        public boolean available() {
            return chatClientProvider.getIfAvailable() != null;
        }

        @Override
        public String generate(String prompt) {
            ChatClient chatClient = chatClientProvider.getIfAvailable();
            if (chatClient == null) {
                return "";
            }
            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CurationDraft(String note, List<CuratedPostDraft> posts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CuratedPostDraft(long postId, String title, String comment) {
    }
}
