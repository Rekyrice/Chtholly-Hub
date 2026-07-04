package com.chtholly.post.service;

import com.chtholly.agent.content.ContentAnalysis;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.chtholly.post.api.dto.PostSummary;
import com.chtholly.post.model.Post;

import java.time.Duration;
import java.util.List;

/**
 * 帖子业务接口。
 */
public interface PostService {

    long createDraft(long creatorId);

    void confirmContent(long creatorId, long id, String objectKey, String etag, Long size, String sha256);

    void updateMetadata(long creatorId, long id, String title, Long tagId, List<String> tags, List<String> imgUrls, String visible, Boolean isTop, String description);

    void publish(long creatorId, long id);

    void updateTop(long creatorId, long id, boolean isTop);

    void updateVisibility(long creatorId, long id, String visible);

    void delete(long creatorId, long id);

    void adminUpdateVisibility(long id, String visible);

    void adminDelete(long id);

    PostDetailResponse getDetail(long id, Long currentUserIdNullable);

    PostDetailResponse getDetailBySlug(String slug, Long currentUserIdNullable);

    List<PostSummary> getRecentPosts(Duration window);

    long countSince(Duration window);

    List<Post> getPostsNeedingUnderstanding();

    void saveContentAnalysis(Long postId, ContentAnalysis analysis);

    ContentAnalysis getContentAnalysis(Long postId);

    ContentAnalysis getContentAnalysisBySlug(String slug);

    /** 详情 ETag：hash(status + layoutVersion + updateTime)。 */
    String computeDetailEtag(long id);

    String computeDetailEtagBySlug(String slug);
}
