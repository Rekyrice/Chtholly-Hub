package com.chtholly.post.service.impl;

import com.chtholly.agent.content.ContentAnalysis;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.post.api.dto.PostSummary;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Read and persistence boundary used by background cognition and content-understanding jobs.
 */
@Service
@Slf4j
public class PostBackgroundQueryService {
    private final PostMapper mapper;
    private final ObjectMapper objectMapper;

    public PostBackgroundQueryService(PostMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns recently published public posts for background agent cognition.
     *
     * @param window Lookback window.
     * @return Recent post summaries.
     */
    public List<PostSummary> getRecentPosts(Duration window) {
        return getRecentPosts(window, 20);
    }

    public List<PostSummary> getRecentPosts(Duration window, int limit) {
        Duration safeWindow = window == null || window.isNegative() || window.isZero()
                ? Duration.ofHours(6)
                : window;
        int safeLimit = Math.clamp(limit, 1, 500);
        Instant since = Instant.now().minus(safeWindow);
        return mapper.listRecentPublicSince(since, safeLimit).stream()
                .map(row -> new PostSummary(
                        row.getId(),
                        row.getTitle(),
                        row.getDescription(),
                        row.getPublishTime(),
                        parseStringArray(row.getTags())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PostSummary> getPostSummariesByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Post> posts = mapper.findByIds(ids);
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }
        Map<Long, Post> byId = posts.stream()
                .filter(p -> p.getId() != null && "published".equals(p.getStatus()))
                .collect(java.util.stream.Collectors.toMap(Post::getId, p -> p, (a, b) -> a));
        List<PostSummary> ordered = new java.util.ArrayList<>();
        for (Long id : ids) {
            Post post = byId.get(id);
            if (post == null) {
                continue;
            }
            ordered.add(new PostSummary(
                    post.getId(),
                    post.getTitle(),
                    post.getDescription(),
                    post.getPublishTime(),
                    parseStringArray(post.getTags())));
        }
        return ordered;
    }

    /**
     * Returns recently published public seed posts for Chtholly audit jobs.
     *
     * @param window Lookback window.
     * @return Recent seed posts.
     */
    @Transactional(readOnly = true)
    public List<Post> getRecentSeedPosts(Duration window) {
        Duration safeWindow = window == null || window.isNegative() || window.isZero()
                ? Duration.ofHours(24)
                : window;
        return mapper.listRecentSeedPosts(Instant.now().minus(safeWindow), 50);
    }

    public long countSince(Duration window) {
        Duration safeWindow = window == null || window.isNegative() || window.isZero()
                ? Duration.ofDays(3)
                : window;
        return mapper.countPublicSince(Instant.now().minus(safeWindow));
    }

    @Transactional(readOnly = true)
    public List<Long> listFirstTimePublisherIds(Duration window) {
        Duration safeWindow = window == null || window.isNegative() || window.isZero()
                ? Duration.ofDays(7)
                : window;
        return mapper.listFirstTimePublisherIdsSince(Instant.now().minus(safeWindow));
    }

    /**
     * Loads public posts whose Agent content understanding is missing or stale.
     *
     * @return posts to analyze in the scheduled understanding job.
     */
    @Transactional(readOnly = true)
    public List<Post> getPostsNeedingUnderstanding() {
        return mapper.listPostsNeedingUnderstanding(20);
    }

    /**
     * Stores Agent content understanding JSON on the post row.
     *
     * @param postId   post ID.
     * @param analysis content understanding result.
     */
    @Transactional
    public void saveContentAnalysis(Long postId, ContentAnalysis analysis) {
        if (postId == null || analysis == null) {
            return;
        }
        try {
            mapper.updateContentAnalysis(
                    postId,
                    objectMapper.writeValueAsString(analysis),
                    analysis.analyzedAt() == null ? Instant.now() : analysis.analyzedAt());
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内容理解结果序列化失败");
        }
    }

    /**
     * Loads stored Agent content understanding for a post.
     *
     * @param postId post ID.
     * @return content analysis, or null when absent.
     */
    @Transactional(readOnly = true)
    public ContentAnalysis getContentAnalysis(Long postId) {
        if (postId == null) {
            return null;
        }
        String json = mapper.findContentAnalysisById(postId);
        return parseContentAnalysisJson(postId, json);
    }

    /**
     * Loads stored Agent content understanding for a public post slug.
     *
     * @param slug post URL slug.
     * @return content analysis, or null when absent.
     */
    @Transactional(readOnly = true)
    public ContentAnalysis getContentAnalysisBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        String json = mapper.findContentAnalysisBySlug(slug.trim());
        return parseContentAnalysisJson(slug, json);
    }

    private ContentAnalysis parseContentAnalysisJson(Object source, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ContentAnalysis.class);
        } catch (Exception e) {
            log.warn("Post content analysis deserialize failed, source={}: {}", source, e.getMessage());
            return null;
        }
    }


    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
