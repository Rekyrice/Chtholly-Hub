package com.chtholly.agent.content;

import com.chtholly.common.scheduler.DistributedLockService;
import com.chtholly.post.api.dto.PostSummary;
import com.chtholly.post.service.PostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
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
        lenient().when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString())).thenReturn(1L);
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
    void clusterRecentPosts_groupsTwoPostsWithOneExactNormalizedTag() {
        when(embeddingModelProvider.getIfAvailable()).thenReturn(null);
        when(postService.getRecentPosts(Duration.ofDays(7), 200)).thenReturn(List.of(
                post(1L, "旅程的终点", "时间与告别", List.of(" 芙莉莲 ")),
                post(2L, "千年的旅途", "关于记忆", List.of("芙莉莲"))
        ));
        when(postService.getContentAnalysis(any())).thenReturn(null);

        List<TopicCluster> clusters = service(prompt -> "", false)
                .clusterRecentPosts(Duration.ofDays(7));

        assertThat(clusters).hasSize(1);
        assertThat(clusters.getFirst().postIds()).containsExactly(1L, 2L);
    }

    @Test
    void clusterRecentPosts_normalizesAndStablySortsFallbackTagEntities() {
        when(embeddingModelProvider.getIfAvailable()).thenReturn(null);
        when(postService.getRecentPosts(Duration.ofDays(7), 200)).thenReturn(List.of(
                post(1L, "First", "first", List.of(" Spring ", " Java ")),
                post(2L, "Second", "second", List.of("spring", "java"))
        ));
        when(postService.getContentAnalysis(any())).thenReturn(null);

        List<TopicCluster> clusters = service(prompt -> "", false)
                .clusterRecentPosts(Duration.ofDays(7));

        assertThat(clusters).singleElement()
                .extracting(TopicCluster::keyEntities)
                .isEqualTo(List.of("java", "spring"));
    }

    @Test
    void clusterRecentPosts_doesNotGroupPostsWithoutSharedTags() {
        when(embeddingModelProvider.getIfAvailable()).thenReturn(null);
        when(postService.getRecentPosts(Duration.ofDays(7), 200)).thenReturn(List.of(
                post(1L, "旅程的终点", "时间与告别", List.of("芙莉莲")),
                post(2L, "CLI 重构", "工程笔记", List.of("Rust"))
        ));

        List<TopicCluster> clusters = service(prompt -> "", false)
                .clusterRecentPosts(Duration.ofDays(7));

        assertThat(clusters).isEmpty();
    }

    @Test
    void clusterRecentPosts_doesNotCreateClusterForSinglePost() {
        when(embeddingModelProvider.getIfAvailable()).thenReturn(null);
        when(postService.getRecentPosts(Duration.ofDays(7), 200)).thenReturn(List.of(
                post(1L, "旅程的终点", "时间与告别", List.of("芙莉莲"))
        ));

        List<TopicCluster> clusters = service(prompt -> "", false)
                .clusterRecentPosts(Duration.ofDays(7));

        assertThat(clusters).isEmpty();
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
    void updateTopicClusters_commitsReadySnapshotAtomically_whenLockAcquired() {
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

        ArgumentCaptor<String> clustersJsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> finalStatusCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis).execute(
                any(DefaultRedisScript.class),
                eq(List.of("agent:topic-clusters", "agent:topic-clusters:status")),
                clustersJsonCaptor.capture(),
                finalStatusCaptor.capture(),
                eq(String.valueOf(Duration.ofHours(24).toMillis())),
                eq(String.valueOf(Duration.ofDays(30).toMillis())));
        ArgumentCaptor<String> pendingStatusCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(
                eq("agent:topic-clusters:status"), pendingStatusCaptor.capture(), eq(Duration.ofDays(30)));
        verify(valueOps, never()).set(eq("agent:topic-clusters"), anyString(), any(Duration.class));
        verify(lockService).unlock("lock:scheduled:topicClustering");
        verify(lockService).recordRun(eq("topicClustering"), any(Long.class), eq(true));
        assertThat(clustersJsonCaptor.getValue()).contains("芙莉莲讨论");
        TopicClusterRunStatus pending = readStatus(pendingStatusCaptor.getValue());
        assertThat(pending.state()).isEqualTo(TopicClusterState.PENDING);
        TopicClusterRunStatus status = readStatus(finalStatusCaptor.getValue());
        assertThat(status.state()).isEqualTo(TopicClusterState.READY);
        assertThat(status.lastAttemptAt()).isEqualTo(NOW);
        assertThat(status.lastSuccessAt()).isEqualTo(NOW);
        assertThat(status.reason()).isNull();
    }

    @Test
    void updateTopicClusters_commitsSparseEmptySnapshotAtomically() {
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(true);
        when(postService.getRecentPosts(Duration.ofDays(7), 200)).thenReturn(List.of());

        TopicClusteringService service = service(prompt -> "", false);
        service.updateTopicClusters();

        ArgumentCaptor<String> clustersJsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis).execute(
                any(DefaultRedisScript.class),
                eq(List.of("agent:topic-clusters", "agent:topic-clusters:status")),
                clustersJsonCaptor.capture(),
                statusCaptor.capture(),
                eq(String.valueOf(Duration.ofHours(24).toMillis())),
                eq(String.valueOf(Duration.ofDays(30).toMillis())));
        assertThat(clustersJsonCaptor.getValue()).isEqualTo("[]");
        verify(valueOps, never()).set(eq("agent:topic-clusters"), anyString(), any(Duration.class));
        TopicClusterRunStatus status = readStatus(statusCaptor.getValue());
        assertThat(status.state()).isEqualTo(TopicClusterState.SPARSE);
        assertThat(status.lastAttemptAt()).isEqualTo(NOW);
        assertThat(status.lastSuccessAt()).isEqualTo(NOW);
        assertThat(status.reason()).isEqualTo("INSUFFICIENT_SIGNALS");
        verify(lockService).recordRun(eq("topicClustering"), any(Long.class), eq(true));
        verify(lockService).unlock("lock:scheduled:topicClustering");
    }

    @Test
    void updateTopicClusters_persistsFailedStateAndRethrowsOriginalFailure() throws Exception {
        RuntimeException failure = new IllegalStateException("recent posts unavailable");
        Instant previousSuccessAt = NOW.minus(Duration.ofHours(2));
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(true);
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(TopicClusterState.READY, previousSuccessAt, previousSuccessAt, null)));
        when(postService.getRecentPosts(Duration.ofDays(7), 200)).thenThrow(failure);

        TopicClusteringService service = service(prompt -> "", false);

        assertThatThrownBy(service::updateTopicClusters).isSameAs(failure);
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(2)).set(
                eq("agent:topic-clusters:status"), statusCaptor.capture(), eq(Duration.ofDays(30)));
        TopicClusterRunStatus status = readStatus(statusCaptor.getAllValues().getLast());
        TopicClusterRunStatus pending = readStatus(statusCaptor.getAllValues().getFirst());
        assertThat(pending.state()).isEqualTo(TopicClusterState.PENDING);
        assertThat(pending.lastAttemptAt()).isEqualTo(NOW);
        assertThat(pending.lastSuccessAt()).isEqualTo(previousSuccessAt);
        assertThat(pending.reason()).isEqualTo("REFRESHING");
        assertThat(status.state()).isEqualTo(TopicClusterState.FAILED);
        assertThat(status.lastAttemptAt()).isEqualTo(NOW);
        assertThat(status.lastSuccessAt()).isEqualTo(previousSuccessAt);
        assertThat(status.reason()).isEqualTo("REFRESH_FAILED");
        verify(lockService).recordRun(eq("topicClustering"), any(Long.class), eq(false));
        verify(lockService).unlock("lock:scheduled:topicClustering");
    }

    @Test
    void updateTopicClusters_marksRunFailedWhenAtomicCommitFails() {
        RuntimeException storageFailure = new IllegalStateException("atomic snapshot write unavailable");
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(true);
        when(postService.getRecentPosts(Duration.ofDays(7), 200)).thenReturn(List.of());
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString())).thenThrow(storageFailure);

        TopicClusteringService service = service(prompt -> "", false);

        assertThatThrownBy(service::updateTopicClusters).isSameAs(storageFailure);
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(2)).set(
                eq("agent:topic-clusters:status"), statusCaptor.capture(), eq(Duration.ofDays(30)));
        verify(valueOps, never()).set(eq("agent:topic-clusters"), anyString(), any(Duration.class));
        assertThat(readStatus(statusCaptor.getAllValues().getLast()).state())
                .isEqualTo(TopicClusterState.FAILED);
        verify(lockService).recordRun(eq("topicClustering"), any(Long.class), eq(false));
        verify(lockService).unlock("lock:scheduled:topicClustering");
    }

    @Test
    void updateTopicClusters_rejectsNullAtomicCommitResult() {
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(true);
        when(postService.getRecentPosts(Duration.ofDays(7), 200)).thenReturn(List.of());
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString())).thenReturn(null);

        TopicClusteringService service = service(prompt -> "", false);

        assertThatThrownBy(service::updateTopicClusters)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Atomic topic snapshot commit failed");
        verify(valueOps, never()).set(eq("agent:topic-clusters"), anyString(), any(Duration.class));
        verify(lockService).recordRun(eq("topicClustering"), any(Long.class), eq(false));
        verify(lockService).unlock("lock:scheduled:topicClustering");
    }

    @Test
    void updateTopicClusters_preservesOriginalFailureWhenFailedStatusCannotBeStored() {
        RuntimeException refreshFailure = new IllegalStateException("recent posts unavailable");
        RuntimeException statusFailure = new IllegalStateException("status write unavailable");
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(true);
        when(postService.getRecentPosts(Duration.ofDays(7), 200)).thenThrow(refreshFailure);
        doNothing().doThrow(statusFailure).when(valueOps)
                .set(eq("agent:topic-clusters:status"), anyString(), eq(Duration.ofDays(30)));

        TopicClusteringService service = service(prompt -> "", false);

        Throwable thrown = catchThrowable(service::updateTopicClusters);
        assertThat(thrown).isSameAs(refreshFailure);
        assertThat(thrown.getSuppressed()).containsExactly(statusFailure);
        verify(lockService).recordRun(eq("topicClustering"), any(Long.class), eq(false));
        verify(lockService).unlock("lock:scheduled:topicClustering");
    }

    @Test
    void updateTopicClusters_preservesFailureWhenStatusWritesThrowSameInstance() {
        RuntimeException statusFailure = new IllegalStateException("status write unavailable");
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(true);
        doThrow(statusFailure).when(valueOps)
                .set(eq("agent:topic-clusters:status"), anyString(), eq(Duration.ofDays(30)));

        TopicClusteringService service = service(prompt -> "", false);

        Throwable thrown = catchThrowable(service::updateTopicClusters);
        assertThat(thrown).isSameAs(statusFailure);
        verify(lockService).recordRun(eq("topicClustering"), any(Long.class), eq(false));
        verify(lockService).unlock("lock:scheduled:topicClustering");
    }

    @Test
    void updateTopicClusters_skipsWhenLockHeld() {
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(false);

        TopicClusteringService service = service(prompt -> "", true);
        service.updateTopicClusters();

        verify(postService, never()).getRecentPosts(any(), anyInt());
        verify(lockService, never()).unlock(anyString());
        verify(valueOps, never()).set(eq("agent:topic-clusters:status"), anyString(), any(Duration.class));
    }

    @Test
    void getOverview_exposesReadySnapshotWithLastRefreshFailure() throws Exception {
        Instant attemptAt = NOW.minusSeconds(30);
        Instant successAt = NOW.minus(Duration.ofHours(6));
        List<TopicCluster> stored = List.of(new TopicCluster(
                "治愈系动画讨论",
                "大家在聊温柔的故事",
                List.of(1L, 2L),
                2,
                List.of("芙莉莲"),
                successAt));
        when(valueOps.get("agent:topic-clusters")).thenReturn(objectMapper.writeValueAsString(stored));
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(TopicClusterState.FAILED, attemptAt, successAt, "REFRESH_FAILED")));

        TopicClusterOverview overview = service(prompt -> "", false).getOverview();

        assertThat(overview.items()).isEqualTo(stored);
        assertThat(overview.state()).isEqualTo(TopicClusterState.READY);
        assertThat(overview.lastAttemptAt()).isEqualTo(attemptAt);
        assertThat(overview.lastSuccessAt()).isEqualTo(successAt);
        assertThat(overview.windowDays()).isEqualTo(7);
        assertThat(overview.reason()).isEqualTo("LAST_REFRESH_FAILED");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"not-json", "null", "[]"})
    void getOverview_exposesFailedStatusWhenNoUsableSnapshotExists(String topicsPayload) throws Exception {
        Instant attemptAt = NOW.minusSeconds(30);
        Instant successAt = NOW.minus(Duration.ofHours(6));
        when(valueOps.get("agent:topic-clusters")).thenReturn(topicsPayload);
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(
                        TopicClusterState.FAILED,
                        attemptAt,
                        successAt,
                        "REFRESH_FAILED")));

        TopicClusterOverview overview = service(prompt -> "", false).getOverview();

        assertThat(overview.items()).isEmpty();
        assertThat(overview.state()).isEqualTo(TopicClusterState.FAILED);
        assertThat(overview.lastAttemptAt()).isEqualTo(attemptAt);
        assertThat(overview.lastSuccessAt()).isEqualTo(successAt);
        assertThat(overview.reason()).isEqualTo("REFRESH_FAILED");
    }

    @Test
    void getOverview_keepsReadySnapshotVisibleWhileRefreshIsPending() throws Exception {
        Instant attemptAt = NOW.minusSeconds(30);
        Instant successAt = NOW.minus(Duration.ofHours(6));
        List<TopicCluster> stored = storedClusters(successAt);
        when(valueOps.get("agent:topic-clusters")).thenReturn(objectMapper.writeValueAsString(stored));
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(TopicClusterState.PENDING, attemptAt, successAt, "REFRESHING")));

        TopicClusterOverview overview = service(prompt -> "", false).getOverview();

        assertThat(overview.items()).isEqualTo(stored);
        assertThat(overview.state()).isEqualTo(TopicClusterState.READY);
        assertThat(overview.lastAttemptAt()).isEqualTo(attemptAt);
        assertThat(overview.lastSuccessAt()).isEqualTo(successAt);
        assertThat(overview.reason()).isNull();
    }

    @Test
    void getOverview_keepsReadySnapshotVisibleWhenStatusIsMissing() throws Exception {
        List<TopicCluster> stored = storedClusters(NOW);
        when(valueOps.get("agent:topic-clusters")).thenReturn(objectMapper.writeValueAsString(stored));
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(null);

        TopicClusterOverview overview = service(prompt -> "", false).getOverview();

        assertThat(overview.items()).isEqualTo(stored);
        assertThat(overview.state()).isEqualTo(TopicClusterState.READY);
        assertThat(overview.lastAttemptAt()).isNull();
        assertThat(overview.lastSuccessAt()).isNull();
        assertThat(overview.reason()).isNull();
    }

    @Test
    void getOverview_keepsReadySnapshotVisibleWhenStatusIsCorrupt() throws Exception {
        List<TopicCluster> stored = storedClusters(NOW);
        when(valueOps.get("agent:topic-clusters")).thenReturn(objectMapper.writeValueAsString(stored));
        when(valueOps.get("agent:topic-clusters:status")).thenReturn("not-json");

        TopicClusterOverview overview = service(prompt -> "", false).getOverview();

        assertThat(overview.items()).isEqualTo(stored);
        assertThat(overview.state()).isEqualTo(TopicClusterState.READY);
        assertThat(overview.lastAttemptAt()).isNull();
        assertThat(overview.lastSuccessAt()).isNull();
        assertThat(overview.reason()).isNull();
    }

    @Test
    void getOverview_keepsReadySnapshotVisibleWhenStatusIsJsonNull() throws Exception {
        List<TopicCluster> stored = storedClusters(NOW);
        when(valueOps.get("agent:topic-clusters")).thenReturn(objectMapper.writeValueAsString(stored));
        when(valueOps.get("agent:topic-clusters:status")).thenReturn("null");

        TopicClusterOverview overview = service(prompt -> "", false).getOverview();

        assertThat(overview.items()).isEqualTo(stored);
        assertThat(overview.state()).isEqualTo(TopicClusterState.READY);
        assertThat(overview.lastAttemptAt()).isNull();
        assertThat(overview.lastSuccessAt()).isNull();
        assertThat(overview.reason()).isNull();
    }

    @Test
    void getOverview_exposesPendingWhenSnapshotWasNeverGenerated() {
        when(valueOps.get("agent:topic-clusters")).thenReturn(null);
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(null);

        TopicClusterOverview overview = service(prompt -> "", false).getOverview();

        assertThat(overview.items()).isEmpty();
        assertThat(overview.state()).isEqualTo(TopicClusterState.PENDING);
        assertThat(overview.lastAttemptAt()).isNull();
        assertThat(overview.lastSuccessAt()).isNull();
        assertThat(overview.reason()).isEqualTo("NOT_GENERATED");
    }

    @Test
    void getOverview_treatsCorruptStatusAsMissing() {
        when(valueOps.get("agent:topic-clusters")).thenReturn("[]");
        when(valueOps.get("agent:topic-clusters:status")).thenReturn("not-json");

        TopicClusterOverview overview = service(prompt -> "", false).getOverview();

        assertThat(overview.state()).isEqualTo(TopicClusterState.PENDING);
        assertThat(overview.reason()).isEqualTo("NOT_GENERATED");
    }

    @Test
    void getOverview_doesNotExposeCorruptTopicsAsReady() throws Exception {
        when(valueOps.get("agent:topic-clusters")).thenReturn("not-json");
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(TopicClusterState.READY, NOW, NOW, null)));

        TopicClusterOverview overview = service(prompt -> "", false).getOverview();

        assertThat(overview.items()).isEmpty();
        assertThat(overview.state()).isEqualTo(TopicClusterState.PENDING);
        assertThat(overview.reason()).isEqualTo("INVALID_SNAPSHOT");
    }

    @Test
    void getStoredClusters_treatsJsonNullAsEmpty() {
        when(valueOps.get("agent:topic-clusters")).thenReturn("null");

        assertThat(service(prompt -> "", false).getStoredClusters()).isEmpty();
    }

    @Test
    void getOverview_treatsJsonNullStatusAsMissing() {
        when(valueOps.get("agent:topic-clusters")).thenReturn("[]");
        when(valueOps.get("agent:topic-clusters:status")).thenReturn("null");

        TopicClusterOverview overview = service(prompt -> "", false).getOverview();

        assertThat(overview.state()).isEqualTo(TopicClusterState.PENDING);
        assertThat(overview.reason()).isEqualTo("NOT_GENERATED");
    }

    @Test
    void getOverview_rejectsReadyStateWithEmptySnapshot() throws Exception {
        when(valueOps.get("agent:topic-clusters")).thenReturn("[]");
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(TopicClusterState.READY, NOW, NOW, null)));

        TopicClusterOverview overview = service(prompt -> "", false).getOverview();

        assertThat(overview.items()).isEmpty();
        assertThat(overview.state()).isEqualTo(TopicClusterState.PENDING);
        assertThat(overview.reason()).isEqualTo("INVALID_SNAPSHOT");
    }

    @Test
    void getOverview_rejectsReadyStateWithMissingSnapshot() throws Exception {
        when(valueOps.get("agent:topic-clusters")).thenReturn(null);
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(TopicClusterState.READY, NOW, NOW, null)));

        TopicClusterOverview overview = service(prompt -> "", false).getOverview();

        assertThat(overview.state()).isEqualTo(TopicClusterState.PENDING);
        assertThat(overview.reason()).isEqualTo("INVALID_SNAPSHOT");
    }

    @Test
    void getOverview_keepsValidNonEmptySnapshotVisibleWhenSparseStatusIsInconsistent() throws Exception {
        List<TopicCluster> clusters = List.of(new TopicCluster(
                "Java",
                "Java 生态讨论",
                List.of(1L, 2L),
                2,
                List.of("java"),
                NOW));
        when(valueOps.get("agent:topic-clusters")).thenReturn(objectMapper.writeValueAsString(clusters));
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(
                        TopicClusterState.SPARSE,
                        NOW,
                        NOW,
                        "INSUFFICIENT_SIGNALS")));

        TopicClusterOverview overview = service(prompt -> "", false).getOverview();

        assertThat(overview.items()).isEqualTo(clusters);
        assertThat(overview.state()).isEqualTo(TopicClusterState.READY);
        assertThat(overview.lastAttemptAt()).isEqualTo(NOW);
        assertThat(overview.lastSuccessAt()).isEqualTo(NOW);
        assertThat(overview.reason()).isNull();
    }

    @Test
    void getOverview_exposesPersistedSparseState() throws Exception {
        Instant attemptAt = NOW.minusSeconds(30);
        when(valueOps.get("agent:topic-clusters")).thenReturn("[]");
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(
                        TopicClusterState.SPARSE,
                        attemptAt,
                        NOW,
                        "INSUFFICIENT_SIGNALS")));

        TopicClusterOverview overview = service(prompt -> "", false).getOverview();

        assertThat(overview.items()).isEmpty();
        assertThat(overview.state()).isEqualTo(TopicClusterState.SPARSE);
        assertThat(overview.lastAttemptAt()).isEqualTo(attemptAt);
        assertThat(overview.lastSuccessAt()).isEqualTo(NOW);
        assertThat(overview.reason()).isEqualTo("INSUFFICIENT_SIGNALS");
    }

    @Test
    void refreshIfMissing_doesNotRefreshValidReadySnapshot() throws Exception {
        List<TopicCluster> clusters = List.of(new TopicCluster(
                "Java", "Java 生态讨论", List.of(1L, 2L), 2, List.of("java"), NOW));
        when(valueOps.get("agent:topic-clusters")).thenReturn(objectMapper.writeValueAsString(clusters));
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(TopicClusterState.READY, NOW, NOW, null)));

        service(prompt -> "", false).refreshIfMissing();

        verify(lockService, never()).tryLock(anyString(), any(Duration.class));
        verify(redis, never()).hasKey(anyString());
    }

    @Test
    void refreshIfMissing_doesNotRefreshValidSparseSnapshot() throws Exception {
        when(valueOps.get("agent:topic-clusters")).thenReturn("[]");
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(
                        TopicClusterState.SPARSE,
                        NOW,
                        NOW,
                        "INSUFFICIENT_SIGNALS")));

        service(prompt -> "", false).refreshIfMissing();

        verify(lockService, never()).tryLock(anyString(), any(Duration.class));
    }

    @Test
    void refreshIfMissing_refreshesWhenSnapshotPayloadIsMissing() throws Exception {
        when(valueOps.get("agent:topic-clusters")).thenReturn(null);
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(TopicClusterState.READY, NOW, NOW, null)));
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(false);

        service(prompt -> "", false).refreshIfMissing();

        verify(lockService).tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15));
    }

    @Test
    void refreshIfMissing_refreshesStalePendingState() throws Exception {
        when(valueOps.get("agent:topic-clusters")).thenReturn(
                objectMapper.writeValueAsString(storedClusters(NOW.minus(Duration.ofHours(6)))));
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(TopicClusterState.PENDING, NOW, null, "REFRESHING")));
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(false);

        service(prompt -> "", false).refreshIfMissing();

        verify(lockService).tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15));
    }

    @Test
    void refreshIfMissing_refreshesMissingStatusWithValidNonEmptySnapshot() throws Exception {
        when(valueOps.get("agent:topic-clusters")).thenReturn(objectMapper.writeValueAsString(storedClusters(NOW)));
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(null);
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(false);

        service(prompt -> "", false).refreshIfMissing();

        verify(lockService).tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15));
    }

    @Test
    void refreshIfMissing_refreshesJsonNullPayloads() {
        when(valueOps.get("agent:topic-clusters")).thenReturn("null");
        when(valueOps.get("agent:topic-clusters:status")).thenReturn("null");
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(false);

        service(prompt -> "", false).refreshIfMissing();

        verify(lockService).tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15));
    }

    @Test
    void refreshIfMissing_refreshesCorruptSnapshotPayload() throws Exception {
        when(valueOps.get("agent:topic-clusters")).thenReturn("not-json");
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(TopicClusterState.READY, NOW, NOW, null)));
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(false);

        service(prompt -> "", false).refreshIfMissing();

        verify(lockService).tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15));
    }

    @Test
    void refreshIfMissing_refreshesBlankSnapshotPayload() throws Exception {
        when(valueOps.get("agent:topic-clusters")).thenReturn("   ");
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(TopicClusterState.SPARSE, NOW, NOW, "INSUFFICIENT_SIGNALS")));
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(false);

        service(prompt -> "", false).refreshIfMissing();

        verify(lockService).tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15));
    }

    @Test
    void refreshIfMissing_refreshesCorruptStatusPayload() throws Exception {
        when(valueOps.get("agent:topic-clusters")).thenReturn(objectMapper.writeValueAsString(storedClusters(NOW)));
        when(valueOps.get("agent:topic-clusters:status")).thenReturn("not-json");
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(false);

        service(prompt -> "", false).refreshIfMissing();

        verify(lockService).tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"not-json", "null", "[]"})
    void refreshIfMissing_refreshesFailedStatusWhenNoUsableSnapshotExists(String topicsPayload) throws Exception {
        when(valueOps.get("agent:topic-clusters")).thenReturn(topicsPayload);
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(
                        TopicClusterState.FAILED,
                        NOW,
                        NOW.minus(Duration.ofHours(6)),
                        "REFRESH_FAILED")));
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(false);

        service(prompt -> "", false).refreshIfMissing();

        verify(lockService).tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15));
    }

    @Test
    void refreshIfMissing_refreshesSparseStateWithNonEmptySnapshot() throws Exception {
        List<TopicCluster> clusters = List.of(new TopicCluster(
                "Java", "Java 生态讨论", List.of(1L, 2L), 2, List.of("java"), NOW));
        when(valueOps.get("agent:topic-clusters")).thenReturn(objectMapper.writeValueAsString(clusters));
        when(valueOps.get("agent:topic-clusters:status")).thenReturn(objectMapper.writeValueAsString(
                new TopicClusterRunStatus(
                        TopicClusterState.SPARSE,
                        NOW,
                        NOW,
                        "INSUFFICIENT_SIGNALS")));
        when(lockService.tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15))).thenReturn(false);

        service(prompt -> "", false).refreshIfMissing();

        verify(lockService).tryLock("lock:scheduled:topicClustering", Duration.ofMinutes(15));
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

    private static List<TopicCluster> storedClusters(Instant generatedAt) {
        return List.of(new TopicCluster(
                "Java",
                "Java ecosystem discussion",
                List.of(1L, 2L),
                2,
                List.of("java"),
                generatedAt));
    }

    private TopicClusterRunStatus readStatus(String json) {
        try {
            return objectMapper.readValue(json, TopicClusterRunStatus.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
