package com.chtholly.post.service.impl;

import com.chtholly.content.ContentAnalysis;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.chtholly.post.api.dto.PostSummary;
import com.chtholly.post.model.Post;
import com.chtholly.post.service.PostService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Compatibility facade for post commands and queries.
 *
 * <p>The facade retains transaction entry points while dedicated collaborators own each use case.
 */
@Service
public class PostServiceImpl implements PostService {

    private final PostDraftCommandService draftCommands;
    private final PostMetadataCommandService metadataCommands;
    private final PostPublicationCommandService publicationCommands;
    private final PostDeletionCommandService deletionCommands;
    private final PostDetailQueryService detailQueries;
    private final PostBackgroundQueryService backgroundQueries;

    /**
     * Creates the post compatibility facade.
     */
    public PostServiceImpl(
            PostDraftCommandService draftCommands,
            PostMetadataCommandService metadataCommands,
            PostPublicationCommandService publicationCommands,
            PostDeletionCommandService deletionCommands,
            PostDetailQueryService detailQueries,
            PostBackgroundQueryService backgroundQueries) {
        this.draftCommands = draftCommands;
        this.metadataCommands = metadataCommands;
        this.publicationCommands = publicationCommands;
        this.deletionCommands = deletionCommands;
        this.detailQueries = detailQueries;
        this.backgroundQueries = backgroundQueries;
    }

    @Override
    @Transactional
    public long createDraft(long creatorId) {
        return draftCommands.createDraft(creatorId);
    }

    @Override
    @Transactional
    public void confirmContent(long creatorId, long id, String objectKey, String etag, Long size, String sha256) {
        draftCommands.confirmContent(creatorId, id, objectKey, etag, size, sha256);
    }

    @Override
    @Transactional
    public void updateMetadata(
            long creatorId,
            long id,
            String title,
            Long tagId,
            List<String> tags,
            List<String> imgUrls,
            String visible,
            Boolean isTop,
            String description) {
        metadataCommands.updateMetadata(
                creatorId, id, title, tagId, tags, imgUrls, visible, isTop, description);
    }

    @Override
    @Transactional
    public void publish(long creatorId, long id) {
        publicationCommands.publish(creatorId, id);
    }

    @Override
    @Transactional
    public void updateTop(long creatorId, long id, boolean isTop) {
        metadataCommands.updateTop(creatorId, id, isTop);
    }

    @Override
    @Transactional
    public void updateVisibility(long creatorId, long id, String visible) {
        metadataCommands.updateVisibility(creatorId, id, visible);
    }

    @Override
    @Transactional
    public void delete(long creatorId, long id) {
        deletionCommands.delete(creatorId, id);
    }

    @Override
    @Transactional
    public void adminUpdateVisibility(long id, String visible) {
        deletionCommands.adminUpdateVisibility(id, visible);
    }

    @Override
    @Transactional
    public void adminDelete(long id) {
        deletionCommands.adminDelete(id);
    }

    @Override
    public PostDetailResponse getDetail(long id, Long currentUserIdNullable) {
        return detailQueries.getDetail(id, currentUserIdNullable);
    }

    @Override
    public PostDetailResponse getDetailBySlug(String slug, Long currentUserIdNullable) {
        return detailQueries.getDetailBySlug(slug, currentUserIdNullable);
    }

    @Override
    public String computeDetailEtag(long id) {
        return detailQueries.computeEtag(id);
    }

    @Override
    public String computeDetailEtagBySlug(String slug) {
        return detailQueries.computeEtagBySlug(slug);
    }

    @Override
    public List<PostSummary> getRecentPosts(Duration window) {
        return backgroundQueries.getRecentPosts(window);
    }

    @Override
    public List<PostSummary> getRecentPosts(Duration window, int limit) {
        return backgroundQueries.getRecentPosts(window, limit);
    }

    @Override
    public List<PostSummary> getPostSummariesByIds(List<Long> ids) {
        return backgroundQueries.getPostSummariesByIds(ids);
    }

    @Override
    public List<Post> getRecentSeedPosts(Duration window) {
        return backgroundQueries.getRecentSeedPosts(window);
    }

    @Override
    public long countSince(Duration window) {
        return backgroundQueries.countSince(window);
    }

    @Override
    public List<Long> listFirstTimePublisherIds(Duration window) {
        return backgroundQueries.listFirstTimePublisherIds(window);
    }

    @Override
    public List<Post> getPostsNeedingUnderstanding() {
        return backgroundQueries.getPostsNeedingUnderstanding();
    }

    @Override
    public void saveContentAnalysis(Long postId, ContentAnalysis analysis) {
        backgroundQueries.saveContentAnalysis(postId, analysis);
    }

    @Override
    public ContentAnalysis getContentAnalysis(Long postId) {
        return backgroundQueries.getContentAnalysis(postId);
    }

    @Override
    public ContentAnalysis getContentAnalysisBySlug(String slug) {
        return backgroundQueries.getContentAnalysisBySlug(slug);
    }
}
