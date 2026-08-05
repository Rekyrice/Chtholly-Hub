package com.chtholly.post.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.post.util.SlugUtils;
import com.chtholly.tag.service.TagService;
import org.springframework.stereotype.Service;

import java.util.List;

/** Coordinates publication persistence and schedules derived work after commit. */
@Service
public class PostPublicationCommandService {

    private final PostMapper mapper;
    private final PostPayloadCodec payloadCodec;
    private final TagService tagService;
    private final PostOutboxWriter outboxWriter;
    private final PostCommittedSideEffectCoordinator sideEffectCoordinator;
    private final PostMutationCacheCoordinator cacheCoordinator;

    /**
     * Creates the publication command service.
     *
     * @param mapper post persistence mapper
     * @param payloadCodec stored JSON codec
     * @param tagService tag aggregate service
     * @param outboxWriter transactional Outbox writer
     * @param sideEffectCoordinator committed side-effect boundary
     * @param cacheCoordinator mutation cache coordinator
     */
    public PostPublicationCommandService(
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

    void publish(long creatorId, long postId) {
        Post lockedDraft = mapper.findDraftByIdForUpdate(postId);
        if (lockedDraft == null || !lockedDraft.getCreatorId().equals(creatorId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        if (mapper.publish(postId, creatorId) == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        Post post = mapper.findById(postId);
        if (post != null && (post.getSlug() == null || post.getSlug().isBlank())) {
            String base = SlugUtils.fromTitle(post.getTitle());
            String unique = SlugUtils.ensureUnique(base, postId, mapper::findIdBySlug);
            mapper.updateSlug(postId, creatorId, unique);
            post = mapper.findById(postId);
        }
        if (post != null) {
            tagService.syncPublishedPostTags(
                    creatorId,
                    List.of(),
                    payloadCodec.parseStringArray(post.getTags()));
        }
        long eventId = outboxWriter.write(postId, "PostPublished", "upsert");
        cacheCoordinator.invalidatePublicationAfterCommit(postId, creatorId);
        sideEffectCoordinator.afterPublished(eventId, postId, creatorId, post);
    }
}
