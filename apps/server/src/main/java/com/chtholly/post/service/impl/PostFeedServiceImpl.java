package com.chtholly.post.service.impl;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.service.PostFeedService;
import org.springframework.stereotype.Service;

/** Compatibility facade for public and user-scoped post feed queries. */
@Service
public class PostFeedServiceImpl implements PostFeedService {

    private final PublicPostFeedQueryService publicFeedQueryService;
    private final PersonalPostFeedService personalFeedService;

    /**
     * Creates the feed compatibility facade.
     *
     * @param publicFeedQueryService public-feed query coordinator
     * @param personalFeedService user-scoped feed service
     */
    public PostFeedServiceImpl(
            PublicPostFeedQueryService publicFeedQueryService,
            PersonalPostFeedService personalFeedService) {
        this.publicFeedQueryService = publicFeedQueryService;
        this.personalFeedService = personalFeedService;
    }

    @Override
    public PageResponse<FeedItemResponse> getPublicFeed(
            Integer page,
            String cursor,
            int size,
            Long ownerId,
            String tag,
            Long currentUserIdNullable) {
        return publicFeedQueryService.getPublicFeed(
                page, cursor, size, ownerId, tag, currentUserIdNullable);
    }

    @Override
    public String publicFeedPageKey(
            Integer page,
            String cursor,
            int size,
            Long ownerId,
            String tag) {
        return publicFeedQueryService.publicFeedPageKey(page, cursor, size, ownerId, tag);
    }

    @Override
    public void invalidateMyPublishedCache(long userId) {
        personalFeedService.invalidateMyPublishedCache(userId);
    }

    @Override
    public PageResponse<FeedItemResponse> getMyPublished(long userId, int page, int size) {
        return personalFeedService.getMyPublished(userId, page, size);
    }

    @Override
    public PageResponse<FeedItemResponse> getFollowingFeed(long userId, int page, int size) {
        return personalFeedService.getFollowingFeed(userId, page, size);
    }
}
