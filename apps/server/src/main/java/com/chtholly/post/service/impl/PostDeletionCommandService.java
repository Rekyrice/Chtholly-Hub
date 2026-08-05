package com.chtholly.post.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.tag.service.TagService;
import org.springframework.stereotype.Service;

import java.util.List;

/** Owns author and administrator visibility/deletion commands. */
@Service
public class PostDeletionCommandService {

    private final PostMapper mapper;
    private final PostPayloadCodec payloadCodec;
    private final TagService tagService;
    private final PostOutboxWriter outboxWriter;
    private final PostCommittedSideEffectCoordinator sideEffectCoordinator;
    private final PostMutationCacheCoordinator cacheCoordinator;

    /**
     * Creates the post deletion command service.
     *
     * @param mapper post persistence mapper
     * @param payloadCodec stored JSON codec
     * @param tagService tag aggregate service
     * @param outboxWriter transactional Outbox writer
     * @param sideEffectCoordinator committed side-effect boundary
     * @param cacheCoordinator mutation cache coordinator
     */
    public PostDeletionCommandService(
            PostMapper mapper,
            PostPayloadCodec payloadCodec,
            TagService tagService,
            PostOutboxWriter outboxWriter,
            PostCommittedSideEffectCoordinator sideEffectCoordinator,
            PostMutationCacheCoordinator cacheCoordinator) {
        this.mapper = mapper;
        this.payloadCodec = payloadCodec;
        this.tagService = tagService;
        this.outboxWriter = outboxWriter;
        this.sideEffectCoordinator = sideEffectCoordinator;
        this.cacheCoordinator = cacheCoordinator;
    }

    void delete(long creatorId, long postId) {
        cacheCoordinator.invalidateBeforeWrite(postId);
        Post existing = mapper.findByIdForUpdate(postId);
        if (existing == null
                || !existing.getCreatorId().equals(creatorId)
                || "deleted".equals(existing.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        boolean wasPublished = "published".equals(existing.getStatus());
        List<String> oldTags = payloadCodec.parseStringArray(existing.getTags());
        if (mapper.softDelete(postId, creatorId) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        if (wasPublished) {
            tagService.releasePublishedPostTags(oldTags);
        }
        long eventId = outboxWriter.write(postId, "PostDeleted", "delete");
        cacheCoordinator.invalidatePostAfterCommit(postId);
        cacheCoordinator.invalidatePublicStructureAfterCommit(creatorId);
        sideEffectCoordinator.afterDeleted(eventId, postId);
    }

    void adminUpdateVisibility(long postId, String visible) {
        PostMetadataCommandService.requireValidVisibility(visible);
        cacheCoordinator.invalidateBeforeWrite(postId);
        Post existing = mapper.findByIdForUpdate(postId);
        if (existing == null || "deleted".equals(existing.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "帖子不存在");
        }
        if (mapper.updateVisibilityById(postId, visible) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "帖子不存在");
        }
        long eventId = outboxWriter.write(postId, "PostVisibilityChanged", "upsert");
        cacheCoordinator.invalidatePostAfterCommit(postId);
        cacheCoordinator.invalidateVisibilityAfterCommit(existing.getCreatorId());
        sideEffectCoordinator.afterVisibilityChanged(eventId, postId);
    }

    void adminDelete(long postId) {
        cacheCoordinator.invalidateBeforeWrite(postId);
        Post existing = mapper.findByIdForUpdate(postId);
        if (existing == null || "deleted".equals(existing.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "帖子不存在");
        }
        boolean wasPublished = "published".equals(existing.getStatus());
        List<String> oldTags = payloadCodec.parseStringArray(existing.getTags());
        if (mapper.softDeleteById(postId) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "帖子不存在");
        }
        if (wasPublished) {
            tagService.releasePublishedPostTags(oldTags);
        }
        long eventId = outboxWriter.write(postId, "PostDeleted", "delete");
        cacheCoordinator.invalidatePostAfterCommit(postId);
        cacheCoordinator.invalidatePublicStructureAfterCommit(existing.getCreatorId());
        sideEffectCoordinator.afterDeleted(eventId, postId);
    }
}
