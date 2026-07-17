package com.chtholly.agent.content;

import com.chtholly.agent.config.AgentExtensionComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.chtholly.common.scheduler.DistributedLockService;
import com.chtholly.content.ContentAnalysis;
import com.chtholly.content.Entity;
import com.chtholly.post.api.dto.PostSummary;
import com.chtholly.post.service.PostService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Discovers community topic clusters via embedding single-pass clustering
 * (with tag co-occurrence fallback) and LLM topic labeling.
 *
 * <p>Results are stored in Redis for Hub discovery and weekly curation.
 */
@Slf4j
@Component
@AgentExtensionComponent
@ConditionalOnProperty(prefix = "agent.extensions.content", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TopicClusteringService {

    private static final String COMMIT_SNAPSHOT_LUA = """
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[4])
            return 1
            """;
    private static final DefaultRedisScript<Long> COMMIT_SNAPSHOT_SCRIPT = createCommitSnapshotScript();

    static final String TOPICS_KEY = "agent:topic-clusters";
    static final String STATUS_KEY = "agent:topic-clusters:status";
    static final String LOCK_KEY = "lock:scheduled:topicClustering";
    static final Duration REDIS_TTL = Duration.ofHours(24);
    static final Duration STATUS_TTL = Duration.ofDays(30);
    static final Duration LOCK_TTL = Duration.ofMinutes(15);
    static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    static final double SIMILARITY_THRESHOLD = 0.7;
    static final int MIN_CLUSTER_SIZE = 2;
    static final int MAX_POSTS = 200;
    static final int MIN_SHARED_TAGS = 1;

    private final PostService postService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final DistributedLockService lockService;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final TextGenerator textGenerator;
    private final Clock clock;

    @Autowired
    public TopicClusteringService(PostService postService,
                                  StringRedisTemplate redis,
                                  ObjectMapper objectMapper,
                                  DistributedLockService lockService,
                                  ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                  ObjectProvider<ChatClient> chatClientProvider) {
        this(postService,
                redis,
                objectMapper,
                lockService,
                embeddingModelProvider,
                new ChatClientTextGenerator(chatClientProvider),
                Clock.systemUTC());
    }

    TopicClusteringService(PostService postService,
                           StringRedisTemplate redis,
                           ObjectMapper objectMapper,
                           DistributedLockService lockService,
                           ObjectProvider<EmbeddingModel> embeddingModelProvider,
                           TextGenerator textGenerator,
                           Clock clock) {
        this.postService = postService;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.lockService = lockService;
        this.embeddingModelProvider = embeddingModelProvider;
        this.textGenerator = textGenerator;
        this.clock = clock;
    }

    /**
     * Refreshes topic clusters every 6 hours.
     */
    @Scheduled(cron = "0 0 */6 * * *")
    public void updateTopicClusters() {
        if (!lockService.tryLock(LOCK_KEY, LOCK_TTL)) {
            log.debug("Topic clustering skipped because lock is held");
            return;
        }
        long startNanos = System.nanoTime();
        boolean success = false;
        Instant attemptAt = clock.instant();
        TopicClusterRunStatus previousStatus = null;
        try {
            previousStatus = getRunStatus();
            Instant previousSuccessAt = previousStatus == null ? null : previousStatus.lastSuccessAt();
            storeRunStatus(new TopicClusterRunStatus(
                    TopicClusterState.PENDING,
                    attemptAt,
                    previousSuccessAt,
                    "REFRESHING"));
            List<TopicCluster> clusters = clusterRecentPosts(DEFAULT_WINDOW);
            boolean empty = clusters.isEmpty();
            TopicClusterRunStatus finalStatus = new TopicClusterRunStatus(
                    empty ? TopicClusterState.SPARSE : TopicClusterState.READY,
                    attemptAt,
                    clock.instant(),
                    empty ? "INSUFFICIENT_SIGNALS" : null);
            commitSnapshot(clusters, finalStatus);
            success = true;
            log.info("Topic clustering stored {} clusters", clusters.size());
        } catch (RuntimeException refreshFailure) {
            Instant previousSuccessAt = previousStatus == null ? null : previousStatus.lastSuccessAt();
            try {
                storeRunStatus(new TopicClusterRunStatus(
                        TopicClusterState.FAILED,
                        attemptAt,
                        previousSuccessAt,
                        "REFRESH_FAILED"));
            } catch (RuntimeException statusFailure) {
                if (statusFailure != refreshFailure) {
                    refreshFailure.addSuppressed(statusFailure);
                }
            }
            log.error("Topic clustering failed", refreshFailure);
            throw refreshFailure;
        } finally {
            long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            try {
                lockService.recordRun("topicClustering", durationMs, success);
            } finally {
                lockService.unlock(LOCK_KEY);
            }
        }
    }

    /**
     * Refreshes topic clusters when persisted snapshot payloads are missing or inconsistent.
     */
    public void refreshIfMissing() {
        if (!loadPersistedSnapshot().reusable()) {
            updateTopicClusters();
        }
    }

    /**
     * Clusters recent public posts in the given lookback window.
     *
     * @param window lookback duration
     * @return clusters with size &gt;= 2, sorted by size descending
     */
    public List<TopicCluster> clusterRecentPosts(Duration window) {
        Duration safeWindow = window == null || window.isNegative() || window.isZero()
                ? DEFAULT_WINDOW
                : window;
        List<PostSummary> posts = postService.getRecentPosts(safeWindow, MAX_POSTS);
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }

        Instant now = clock.instant();
        List<MutableCluster> raw = embedAvailable()
                ? clusterByEmbedding(posts)
                : clusterByTags(posts);

        List<TopicCluster> result = new ArrayList<>();
        for (MutableCluster cluster : raw) {
            if (cluster.members.size() < MIN_CLUSTER_SIZE) {
                continue;
            }
            List<String> keyEntities = collectKeyEntities(cluster.members);
            TopicLabel label = labelCluster(cluster.members, keyEntities);
            List<Long> postIds = cluster.members.stream()
                    .map(PostSummary::id)
                    .filter(Objects::nonNull)
                    .toList();
            result.add(new TopicCluster(
                    label.topicName(),
                    label.summary(),
                    postIds,
                    postIds.size(),
                    keyEntities,
                    now));
        }
        result.sort(Comparator.comparingInt(TopicCluster::size).reversed());
        return result;
    }

    /**
     * Returns clusters currently stored in Redis.
     *
     * @return stored clusters, or empty when missing/invalid
     */
    public List<TopicCluster> getStoredClusters() {
        return readClusterPayload().clusters();
    }

    /**
     * Returns the current topic-cluster snapshot with lifecycle metadata.
     *
     * @return current topic-cluster overview
     */
    public TopicClusterOverview getOverview() {
        PersistedSnapshot snapshot = loadPersistedSnapshot();
        List<TopicCluster> clusters = snapshot.clusters();
        TopicClusterRunStatus status = snapshot.status();
        if (status == null) {
            return new TopicClusterOverview(
                    List.of(),
                    TopicClusterState.PENDING,
                    null,
                    null,
                    Math.toIntExact(DEFAULT_WINDOW.toDays()),
                    "NOT_GENERATED");
        }
        if (!snapshot.reusable()) {
            String reason = status.state() == TopicClusterState.PENDING
                    ? status.reason()
                    : "INVALID_SNAPSHOT";
            return new TopicClusterOverview(
                    List.of(),
                    TopicClusterState.PENDING,
                    status.lastAttemptAt(),
                    status.lastSuccessAt(),
                    Math.toIntExact(DEFAULT_WINDOW.toDays()),
                    reason == null || reason.isBlank() ? "REFRESHING" : reason);
        }
        if (status.state() == TopicClusterState.FAILED && !clusters.isEmpty()) {
            return new TopicClusterOverview(
                    clusters,
                    TopicClusterState.READY,
                    status.lastAttemptAt(),
                    status.lastSuccessAt(),
                    Math.toIntExact(DEFAULT_WINDOW.toDays()),
                    "LAST_REFRESH_FAILED");
        }
        return new TopicClusterOverview(
                clusters,
                status.state(),
                status.lastAttemptAt(),
                status.lastSuccessAt(),
                Math.toIntExact(DEFAULT_WINDOW.toDays()),
                status.reason());
    }

    /**
     * Finds a stored cluster by topic name (case-insensitive).
     *
     * @param topicName topic label
     * @return matching cluster or null
     */
    public TopicCluster findByTopicName(String topicName) {
        if (topicName == null || topicName.isBlank()) {
            return null;
        }
        String needle = topicName.trim().toLowerCase(Locale.ROOT);
        return getStoredClusters().stream()
                .filter(c -> c.topicName() != null
                        && c.topicName().trim().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst()
                .orElse(null);
    }

    private TopicClusterRunStatus getRunStatus() {
        String raw = redis.opsForValue().get(STATUS_KEY);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            TopicClusterRunStatus status = objectMapper.readValue(raw, TopicClusterRunStatus.class);
            if (status == null || status.state() == null) {
                log.warn("Deserialize topic cluster status produced a missing state");
                return null;
            }
            return status;
        } catch (JsonProcessingException e) {
            log.warn("Deserialize topic cluster status failed: {}", e.getMessage());
            return null;
        }
    }

    private PersistedSnapshot loadPersistedSnapshot() {
        ClusterPayload clusterPayload = readClusterPayload();
        return new PersistedSnapshot(clusterPayload.clusters(), clusterPayload.valid(), getRunStatus());
    }

    private ClusterPayload readClusterPayload() {
        String raw = redis.opsForValue().get(TOPICS_KEY);
        if (raw == null || raw.isBlank()) {
            return new ClusterPayload(List.of(), false);
        }
        try {
            List<TopicCluster> clusters = objectMapper.readValue(raw, new TypeReference<>() {
            });
            if (clusters == null) {
                log.warn("Deserialize topic clusters produced a null snapshot");
                return new ClusterPayload(List.of(), false);
            }
            return new ClusterPayload(List.copyOf(clusters), true);
        } catch (Exception e) {
            log.warn("Deserialize topic clusters failed: {}", e.getMessage());
            return new ClusterPayload(List.of(), false);
        }
    }

    private void storeRunStatus(TopicClusterRunStatus status) {
        try {
            redis.opsForValue().set(STATUS_KEY, objectMapper.writeValueAsString(status), STATUS_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialize topic cluster status failed", e);
        }
    }

    private void commitSnapshot(List<TopicCluster> clusters, TopicClusterRunStatus finalStatus) {
        String clustersJson = serialize(clusters == null ? List.of() : clusters, "topic clusters");
        String statusJson = serialize(finalStatus, "topic cluster status");
        Long committed = redis.execute(
                COMMIT_SNAPSHOT_SCRIPT,
                List.of(TOPICS_KEY, STATUS_KEY),
                clustersJson,
                statusJson,
                String.valueOf(REDIS_TTL.toMillis()),
                String.valueOf(STATUS_TTL.toMillis()));
        if (!Long.valueOf(1L).equals(committed)) {
            throw new IllegalStateException("Atomic topic snapshot commit failed");
        }
    }

    private String serialize(Object value, String description) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialize " + description + " failed", e);
        }
    }

    private static DefaultRedisScript<Long> createCommitSnapshotScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(COMMIT_SNAPSHOT_LUA);
        script.setResultType(Long.class);
        return script;
    }

    private boolean embedAvailable() {
        return embeddingModelProvider != null && embeddingModelProvider.getIfAvailable() != null;
    }

    private List<MutableCluster> clusterByEmbedding(List<PostSummary> posts) {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        List<MutableCluster> clusters = new ArrayList<>();
        for (PostSummary post : posts) {
            float[] vector = embed(model, embedText(post));
            if (vector.length == 0) {
                // embedding 失败时退到标签路径的单点簇，后续仍可被标签合并
                MutableCluster alone = new MutableCluster();
                alone.members.add(post);
                alone.tags.addAll(safeTags(post));
                clusters.add(alone);
                continue;
            }
            int bestIdx = -1;
            double bestSim = -1.0;
            for (int i = 0; i < clusters.size(); i++) {
                MutableCluster existing = clusters.get(i);
                if (existing.centroid == null || existing.centroid.length == 0) {
                    continue;
                }
                double sim = cosine(existing.centroid, vector);
                if (sim > bestSim) {
                    bestSim = sim;
                    bestIdx = i;
                }
            }
            if (bestIdx >= 0 && bestSim >= SIMILARITY_THRESHOLD) {
                MutableCluster target = clusters.get(bestIdx);
                target.members.add(post);
                target.tags.addAll(safeTags(post));
                target.centroid = average(target.centroid, vector, target.members.size());
            } else {
                MutableCluster created = new MutableCluster();
                created.members.add(post);
                created.tags.addAll(safeTags(post));
                created.centroid = vector.clone();
                clusters.add(created);
            }
        }
        return clusters;
    }

    private List<MutableCluster> clusterByTags(List<PostSummary> posts) {
        List<MutableCluster> clusters = new ArrayList<>();
        for (PostSummary post : posts) {
            Set<String> postTags = normalizeTags(safeTags(post));
            int bestIdx = -1;
            int bestShared = 0;
            for (int i = 0; i < clusters.size(); i++) {
                int shared = intersectionSize(postTags, clusters.get(i).tags);
                if (shared > bestShared) {
                    bestShared = shared;
                    bestIdx = i;
                }
            }
            // 精确规范化标签达到可信阈值时才合并，避免热门标签替代真实聚类结果
            if (bestIdx >= 0 && bestShared >= MIN_SHARED_TAGS) {
                MutableCluster target = clusters.get(bestIdx);
                target.members.add(post);
                target.tags.addAll(postTags);
            } else {
                MutableCluster created = new MutableCluster();
                created.members.add(post);
                created.tags.addAll(postTags);
                clusters.add(created);
            }
        }
        return clusters;
    }

    private TopicLabel labelCluster(List<PostSummary> members, List<String> keyEntities) {
        String fallbackName = keyEntities.isEmpty()
                ? truncate(nullToBlank(members.getFirst().title()), 20)
                : truncate(String.join("·", keyEntities.stream().limit(3).toList()), 20);
        String fallbackSummary = truncate(
                members.stream()
                        .map(p -> nullToBlank(p.description()).isBlank() ? nullToBlank(p.title()) : p.description())
                        .filter(s -> !s.isBlank())
                        .findFirst()
                        .orElse("大家在聊相关话题"),
                50);

        if (!textGenerator.available()) {
            return new TopicLabel(fallbackName, fallbackSummary);
        }

        String articles = members.stream()
                .limit(8)
                .map(p -> "- 《" + nullToBlank(p.title()) + "》：" + nullToBlank(p.description()))
                .collect(Collectors.joining("\n"));
        String prompt = """
                以下是属于同一个话题的几篇文章标题和摘要：
                %s
                请用一句话描述这个话题（20 字以内），
                再用一句话总结大家对这个话题的讨论（50 字以内）。
                只输出 JSON：{ "topicName": "...", "summary": "..." }
                """.formatted(articles);
        try {
            String raw = textGenerator.generate(prompt);
            if (raw == null || raw.isBlank()) {
                return new TopicLabel(fallbackName, fallbackSummary);
            }
            TopicLabelDraft draft = objectMapper.readValue(extractJsonObject(raw), TopicLabelDraft.class);
            String name = draft.topicName() == null || draft.topicName().isBlank()
                    ? fallbackName
                    : truncate(draft.topicName().trim(), 20);
            String summary = draft.summary() == null || draft.summary().isBlank()
                    ? fallbackSummary
                    : truncate(draft.summary().trim(), 50);
            return new TopicLabel(name, summary);
        } catch (Exception e) {
            log.warn("Topic label LLM failed: {}", e.getMessage());
            return new TopicLabel(fallbackName, fallbackSummary);
        }
    }

    private List<String> collectKeyEntities(List<PostSummary> members) {
        Map<String, Integer> counts = new HashMap<>();
        for (PostSummary post : members) {
            if (post.id() == null) {
                continue;
            }
            ContentAnalysis analysis = postService.getContentAnalysis(post.id());
            if (analysis == null || analysis.entities() == null) {
                for (String tag : normalizeTags(safeTags(post))) {
                    counts.merge(tag, 1, Integer::sum);
                }
                continue;
            }
            for (Entity entity : analysis.entities()) {
                if (entity == null || entity.name() == null || entity.name().isBlank()) {
                    continue;
                }
                if (entity.confidence() < 0.5) {
                    continue;
                }
                counts.merge(entity.name().trim(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();
    }

    private String embedText(PostSummary post) {
        StringBuilder sb = new StringBuilder();
        sb.append(nullToBlank(post.title()));
        if (post.description() != null && !post.description().isBlank()) {
            sb.append('\n').append(post.description());
        }
        if (post.id() != null) {
            ContentAnalysis analysis = postService.getContentAnalysis(post.id());
            if (analysis != null && analysis.summary() != null && !analysis.summary().isBlank()) {
                sb.append('\n').append(analysis.summary());
            }
        }
        return sb.toString();
    }

    private float[] embed(EmbeddingModel model, String text) {
        if (model == null || text == null || text.isBlank()) {
            return new float[0];
        }
        try {
            float[] vector = model.embed(text);
            return vector == null ? new float[0] : vector;
        } catch (Exception e) {
            log.warn("Embedding failed for topic clustering: {}", e.getMessage());
            return new float[0];
        }
    }

    private static float[] average(float[] centroid, float[] next, int newSize) {
        if (centroid == null || centroid.length == 0) {
            return next.clone();
        }
        if (next.length != centroid.length || newSize <= 1) {
            return centroid;
        }
        float[] updated = new float[centroid.length];
        // 增量均值：new = old + (x - old) / n
        for (int i = 0; i < centroid.length; i++) {
            updated[i] = centroid[i] + (next[i] - centroid[i]) / newSize;
        }
        return updated;
    }

    private static double cosine(float[] left, float[] right) {
        if (left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static List<String> safeTags(PostSummary post) {
        return post.tags() == null ? List.of() : post.tags();
    }

    private static Set<String> normalizeTags(List<String> tags) {
        Set<String> normalized = new HashSet<>();
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            normalized.add(tag.trim().toLowerCase(Locale.ROOT));
        }
        return normalized;
    }

    private static int intersectionSize(Set<String> left, Set<String> right) {
        int count = 0;
        for (String item : left) {
            if (right.contains(item)) {
                count++;
            }
        }
        return count;
    }

    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return raw;
        }
        return raw.substring(start, end + 1);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max);
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
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
            return chatClientProvider != null && chatClientProvider.getIfAvailable() != null;
        }

        @Override
        public String generate(String prompt) {
            ChatClient chatClient = chatClientProvider == null ? null : chatClientProvider.getIfAvailable();
            if (chatClient == null) {
                return "";
            }
            try {
                String content = chatClient.prompt().user(prompt).call().content();
                return content == null ? "" : content;
            } catch (Exception e) {
                return "";
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TopicLabelDraft(String topicName, String summary) {
    }

    private record TopicLabel(String topicName, String summary) {
    }

    private static final class MutableCluster {
        private final List<PostSummary> members = new ArrayList<>();
        private final Set<String> tags = new HashSet<>();
        private float[] centroid;
    }

    private record ClusterPayload(List<TopicCluster> clusters, boolean valid) {
    }

    private record PersistedSnapshot(
            List<TopicCluster> clusters,
            boolean topicsValid,
            TopicClusterRunStatus status
    ) {
        private boolean reusable() {
            if (!topicsValid || status == null) {
                return false;
            }
            return switch (status.state()) {
                case READY -> !clusters.isEmpty();
                case SPARSE -> clusters.isEmpty();
                case FAILED -> true;
                case PENDING -> false;
            };
        }
    }
}
