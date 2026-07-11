package com.chtholly.agent.api;

import com.chtholly.agent.config.AgentExtensionComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.chtholly.agent.api.dto.TopicClusterResponse;
import com.chtholly.agent.api.dto.TopicPostResponse;
import com.chtholly.agent.content.TopicCluster;
import com.chtholly.agent.content.TopicClusteringService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.post.api.dto.PostSummary;
import com.chtholly.post.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public read API for community topic clusters.
 */
@RestController
@AgentExtensionComponent
@ConditionalOnProperty(prefix = "agent.extensions.content", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping(path = "/api/v1/topics", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class TopicController {

    private final TopicClusteringService topicClusteringService;
    private final PostService postService;

    /**
     * Returns current topic clusters (name, size, summary).
     *
     * @return topic list sorted by heat
     */
    @GetMapping
    public List<TopicClusterResponse> listTopics() {
        return topicClusteringService.getStoredClusters().stream()
                .map(cluster -> new TopicClusterResponse(
                        cluster.topicName(),
                        cluster.summary(),
                        cluster.size(),
                        cluster.keyEntities(),
                        cluster.clusteredAt()))
                .toList();
    }

    /**
     * Returns posts belonging to a topic cluster.
     *
     * @param topicName topic label
     * @return post summaries under the topic
     */
    @GetMapping("/{topicName}/posts")
    public List<TopicPostResponse> listTopicPosts(@PathVariable("topicName") String topicName) {
        TopicCluster cluster = topicClusteringService.findByTopicName(topicName);
        if (cluster == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "话题不存在");
        }
        List<PostSummary> posts = postService.getPostSummariesByIds(cluster.postIds());
        return posts.stream()
                .map(post -> new TopicPostResponse(
                        post.id(),
                        post.title(),
                        post.description(),
                        post.publishTime(),
                        post.tags()))
                .toList();
    }
}
