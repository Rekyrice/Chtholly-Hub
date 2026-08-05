package com.chtholly.post.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.tag.service.TagService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Owns author metadata, pin, and visibility mutation commands. */
@Service
public class PostMetadataCommandService {

    private final PostMapper mapper;
    private final PostPayloadCodec payloadCodec;
    private final TagService tagService;
    private final PostOutboxWriter outboxWriter;
    private final PostCommittedSideEffectCoordinator sideEffectCoordinator;
    private final PostMutationCacheCoordinator cacheCoordinator;

    /**
     * Creates the post metadata command service.
     *
     * @param mapper post persistence mapper
     * @param payloadCodec stored JSON codec
     * @param tagService tag aggregate service
     * @param outboxWriter transactional Outbox writer
     * @param sideEffectCoordinator committed side-effect boundary
     * @param cacheCoordinator mutation cache coordinator
     */
    public PostMetadataCommandService(
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

    void updateMetadata(
            long creatorId,
            long postId,
            String title,
            Long tagId,
            List<String> tags,
            List<String> imageUrls,
            String visible,
            Boolean top,
            String description) {
        if (visible != null) {
            requireValidVisibility(visible);
        }
        cacheCoordinator.invalidateBeforeWrite(postId);
        Post existing = mapper.findByIdForUpdate(postId);
        if (existing == null
                || !existing.getCreatorId().equals(creatorId)
                || "deleted".equals(existing.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        List<String> oldTags = payloadCodec.parseStringArray(existing.getTags());
        boolean wasPublished = "published".equals(existing.getStatus());
        Post post = Post.builder()
                .id(postId)
                .creatorId(creatorId)
                .title(title)
                .tagId(tagId)
                .tags(payloadCodec.toJsonOrNull(tags))
                .imgUrls(payloadCodec.toJsonOrNull(imageUrls))
                .visible(visible)
                .isTop(top)
                .description(description)
                .type("image_text")
                .updateTime(Instant.now())
                .build();
        if (mapper.updateMetadata(post) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        if (wasPublished && tags != null) {
            tagService.syncPublishedPostTags(creatorId, oldTags, tags);
        }
        long eventId = outboxWriter.write(postId, "PostMetadataUpdated", "upsert");
        cacheCoordinator.invalidatePostAfterCommit(postId);
        if (wasPublished && (visible != null || top != null)) {
            cacheCoordinator.invalidatePublicStructureAfterCommit(creatorId);
        } else {
            cacheCoordinator.invalidateAuthorFeedsAfterCommit(creatorId);
        }
        sideEffectCoordinator.afterMetadataUpdated(eventId, postId);
    }

    void updateTop(long creatorId, long postId, boolean top) {
        cacheCoordinator.invalidateBeforeWrite(postId);
        Post existing = mapper.findByIdForUpdate(postId);
        if (existing == null
                || !existing.getCreatorId().equals(creatorId)
                || "deleted".equals(existing.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        if (mapper.updateTop(postId, creatorId, top) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        long eventId = outboxWriter.write(postId, "PostTopChanged", "upsert");
        cacheCoordinator.invalidatePostAfterCommit(postId);
        cacheCoordinator.invalidateAuthorFeedsAfterCommit(creatorId);
        sideEffectCoordinator.afterTopChanged(eventId, postId);
    }

    void updateVisibility(long creatorId, long postId, String visible) {
        requireValidVisibility(visible);
        cacheCoordinator.invalidateBeforeWrite(postId);
        Post existing = mapper.findByIdForUpdate(postId);
        if (existing == null
                || !existing.getCreatorId().equals(creatorId)
                || "deleted".equals(existing.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        if (mapper.updateVisibility(postId, creatorId, visible) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        long eventId = outboxWriter.write(postId, "PostVisibilityChanged", "upsert");
        cacheCoordinator.invalidatePostAfterCommit(postId);
        cacheCoordinator.invalidateVisibilityAfterCommit(creatorId);
        sideEffectCoordinator.afterVisibilityChanged(eventId, postId);
    }

    static void requireValidVisibility(String visible) {
        boolean valid = visible != null && switch (visible) {
            case "public", "followers", "school", "private", "unlisted" -> true;
            default -> false;
        };
        if (!valid) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "可见性取值非法");
        }
    }
}
