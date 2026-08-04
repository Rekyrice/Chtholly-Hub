package com.chtholly.post.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.counter.service.UserCounterService;
import com.chtholly.post.event.PostPublishedEvent;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.post.util.SlugUtils;
import com.chtholly.tag.service.TagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

/** Coordinates publication persistence and its ordered synchronous side effects. */
@Service
public class PostPublicationCommandService {

    private static final Logger log = LoggerFactory.getLogger(PostPublicationCommandService.class);

    private final PostMapper mapper;
    private final PostPayloadCodec payloadCodec;
    private final TagService tagService;
    private final UserCounterService userCounterService;
    private final PostOutboxWriter outboxWriter;
    private final PostSearchCoordinator searchCoordinator;
    private final ApplicationEventPublisher eventPublisher;
    private final PostMutationCacheCoordinator cacheCoordinator;

    /**
     * Creates the publication command service.
     *
     * @param mapper post persistence mapper
     * @param payloadCodec stored JSON codec
     * @param tagService tag aggregate service
     * @param userCounterService author counter service
     * @param outboxWriter transactional Outbox writer
     * @param searchCoordinator best-effort search coordinator
     * @param eventPublisher domain event publisher
     * @param cacheCoordinator mutation cache coordinator
     */
    public PostPublicationCommandService(
            PostMapper mapper,
            PostPayloadCodec payloadCodec,
            TagService tagService,
            UserCounterService userCounterService,
            PostOutboxWriter outboxWriter,
            PostSearchCoordinator searchCoordinator,
            ApplicationEventPublisher eventPublisher,
            PostMutationCacheCoordinator cacheCoordinator) {
        this.mapper = mapper;
        this.payloadCodec = payloadCodec;
        this.tagService = tagService;
        this.userCounterService = userCounterService;
        this.outboxWriter = outboxWriter;
        this.searchCoordinator = searchCoordinator;
        this.eventPublisher = eventPublisher;
        this.cacheCoordinator = cacheCoordinator;
    }

    void publish(long creatorId, long postId) {
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
        try {
            userCounterService.incrementPosts(creatorId, 1);
        } catch (Exception failure) {
            log.warn("Increment posts counter failed after publish, userId={}, postId={}: {}",
                    creatorId, postId, failure.getMessage());
        }
        outboxWriter.write(postId, "PostPublished", "upsert");
        searchCoordinator.upsert(postId);
        searchCoordinator.preIndexAfterPublish(postId);
        publishEvent(postId, creatorId, post);
        cacheCoordinator.invalidatePublicationAfterCommit(postId, creatorId);
    }

    private void publishEvent(long postId, long creatorId, Post post) {
        if (post == null || post.getPublishTime() == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new PostPublishedEvent(
                    postId,
                    creatorId,
                    post.getPublishTime(),
                    post.getVisible()));
        } catch (Exception failure) {
            log.warn("PostPublishedEvent failed, postId={}: {}", postId, failure.getMessage());
        }
    }
}
