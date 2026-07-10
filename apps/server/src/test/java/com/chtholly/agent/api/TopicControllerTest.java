package com.chtholly.agent.api;

import com.chtholly.agent.content.TopicCluster;
import com.chtholly.agent.content.TopicClusteringService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.post.api.dto.PostSummary;
import com.chtholly.post.service.PostService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicControllerTest {

    @Mock
    private TopicClusteringService topicClusteringService;
    @Mock
    private PostService postService;

    @InjectMocks
    private TopicController controller;

    @Test
    void listTopics_returnsStoredClusters() {
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        when(topicClusteringService.getStoredClusters()).thenReturn(List.of(
                new TopicCluster("治愈讨论", "温柔的故事", List.of(1L, 2L), 2, List.of("芙莉莲"), now)
        ));

        assertThat(controller.listTopics()).hasSize(1);
        assertThat(controller.listTopics().getFirst().topicName()).isEqualTo("治愈讨论");
        assertThat(controller.listTopics().getFirst().size()).isEqualTo(2);
    }

    @Test
    void listTopicPosts_returnsSummaries() {
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        when(topicClusteringService.findByTopicName("治愈讨论")).thenReturn(
                new TopicCluster("治愈讨论", "温柔的故事", List.of(1L, 2L), 2, List.of("芙莉莲"), now));
        when(postService.getPostSummariesByIds(List.of(1L, 2L))).thenReturn(List.of(
                new PostSummary(1L, "A", "a", now, List.of("治愈")),
                new PostSummary(2L, "B", "b", now, List.of("治愈"))
        ));

        assertThat(controller.listTopicPosts("治愈讨论")).hasSize(2);
        assertThat(controller.listTopicPosts("治愈讨论").getFirst().title()).isEqualTo("A");
    }

    @Test
    void listTopicPosts_throwsWhenMissing() {
        when(topicClusteringService.findByTopicName("不存在")).thenReturn(null);

        assertThatThrownBy(() -> controller.listTopicPosts("不存在"))
                .isInstanceOf(BusinessException.class);
    }
}
