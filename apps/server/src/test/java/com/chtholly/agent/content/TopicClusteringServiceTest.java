package com.chtholly.agent.content;

import com.chtholly.common.scheduler.DistributedLockService;
import com.chtholly.post.api.dto.PostSummary;
import com.chtholly.post.service.PostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicClusteringServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Mock
    private PostService postService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private DistributedLockService lockService;
    @Mock
    private ObjectProvider<EmbeddingModel> embeddingModelProvider;
    @Mock
    private EmbeddingModel embeddingModel;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void clusterRecentPosts_fallsBackToTagClustering_whenEmbeddingUnavailable() {
        when(embeddingModelProvider.getIfAvailable()).thenReturn(null);
        when(postService.getRecentPosts(Duration.ofDays(7), 200)).thenReturn(List.of(
                post(1L, "芙莉莲观后感", "时间与告别", List.of("芙莉莲", "治愈", "动画")),
                post(2L, "再谈芙莉莲", "关于旅程", List.of("芙莉莲", "治愈", "动画")),
                post(3L, "Rust CLI", "重构笔记", List.of("Rust", "工程"))
        ));
        when(postService.getContentAnalysis(any())).thenReturn(null);

        TopicClusteringService service = service(prompt -> "", false);
        List<TopicCluster> clusters = service.clusterRecentPosts(Duration.ofDays(7));

        assertThat(clusters).hasSize(1);
        assertThat(clusters.getFirst().postIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(clusters.getFirst().size()).isEqualTo(2);
    }

    @Test
    void clusterRecentPosts_groupsByEmbeddingSimilarity() {
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        when(postService.getRecentPosts(Duration.ofDays(7), 200)).thenReturn(List.of(
                post(1L, "A", "a", List.of()),
                post(2L, "B", "b", List.of()),
                post(3L, "C", "c", List.of())
        ));
        when(postService.getContentAnalysis(any())).thenReturn(null);
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if (text.startsWith("A") || text.startsWith("B")) {
                return new float[]{1f, 0f, 0f};
            }
            return new float[]{0f, 1f, 0f};
        });

        TopicClusteringService service = service("""
                {"topicName":"治愈讨论","summary":"大家在聊告别与旅程。"}
                """, true);
        List<TopicCluster> clusters = service.clusterRecentPosts(Duration.ofDays(7));

        assertThat(clusters).hasSize(1);
        assertThat(clusters.getFirst().topicName()).isEqualTo("治愈讨论");
        assertThat(clusters.getFirst().summary()).isEqualTo("大家在聊告别与旅程。");
        assertThat(clusters.getFirst().postIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void updateTopicClusters_storesJsonInRedis_whenLockAcquired() {
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(true);
        when(embeddingModelProvider.getIfAvailable()).thenReturn(null);
        when(postService.getRecentPosts(Duration.ofDays(7), 200)).thenReturn(List.of(
                post(1L, "芙莉莲观后感", "时间与告别", List.of("芙莉莲", "治愈", "动画")),
                post(2L, "再谈芙莉莲", "关于旅程", List.of("芙莉莲", "治愈", "动画"))
        ));
        when(postService.getContentAnalysis(any())).thenReturn(null);

        TopicClusteringService service = service("""
                {"topicName":"芙莉莲讨论","summary":"关于时间与旅程。"}
                """, true);
        service.updateTopicClusters();

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("agent:topic-clusters"), jsonCaptor.capture(), eq(Duration.ofHours(24)));
        verify(lockService).unlock("lock:scheduled:topicClustering");
        verify(lockService).recordRun(eq("topicClustering"), any(Long.class), eq(true));
        assertThat(jsonCaptor.getValue()).contains("芙莉莲讨论");
    }

    @Test
    void updateTopicClusters_skipsWhenLockHeld() {
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(false);

        TopicClusteringService service = service(prompt -> "", true);
        service.updateTopicClusters();

        verify(postService, never()).getRecentPosts(any(), anyInt());
        verify(lockService, never()).unlock(anyString());
    }

    @Test
    void findByTopicName_readsStoredClusters() throws Exception {
        List<TopicCluster> stored = List.of(new TopicCluster(
                "治愈系动画讨论",
                "大家在聊温柔的故事",
                List.of(1L, 2L),
                2,
                List.of("芙莉莲"),
                NOW));
        when(valueOps.get("agent:topic-clusters")).thenReturn(objectMapper.writeValueAsString(stored));

        TopicClusteringService service = service(prompt -> "", false);
        TopicCluster found = service.findByTopicName("治愈系动画讨论");

        assertThat(found).isNotNull();
        assertThat(found.postIds()).containsExactly(1L, 2L);
    }

    private TopicClusteringService service(String llmResponse, boolean llmAvailable) {
        return service(prompt -> llmResponse, llmAvailable);
    }

    private TopicClusteringService service(TopicClusteringService.TextGenerator generator, boolean llmAvailable) {
        TopicClusteringService.TextGenerator wrapped = new TopicClusteringService.TextGenerator() {
            @Override
            public boolean available() {
                return llmAvailable;
            }

            @Override
            public String generate(String prompt) {
                return generator.generate(prompt);
            }
        };
        return new TopicClusteringService(
                postService,
                redis,
                objectMapper,
                lockService,
                embeddingModelProvider,
                wrapped,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PostSummary post(long id, String title, String description, List<String> tags) {
        return new PostSummary(id, title, description, NOW.minus(Duration.ofHours(id)), tags);
    }
}
