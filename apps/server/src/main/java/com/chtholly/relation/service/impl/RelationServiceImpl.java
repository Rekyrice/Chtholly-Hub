package com.chtholly.relation.service.impl;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.profile.api.dto.ProfileResponse;
import com.chtholly.relation.service.RelationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Compatibility facade preserving the public relation service contract. */
@Service
public class RelationServiceImpl implements RelationService {

    private final RelationCommandService commandService;
    private final RelationQueryService queryService;

    /** Creates the facade from relation command and query use cases. */
    public RelationServiceImpl(
            RelationCommandService commandService,
            RelationQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /** Delegates one follow command to the transactional command service. */
    @Override
    public boolean follow(long fromUserId, long toUserId) {
        return commandService.follow(fromUserId, toUserId);
    }

    /** Delegates one unfollow command to the transactional command service. */
    @Override
    public boolean unfollow(long fromUserId, long toUserId) {
        return commandService.unfollow(fromUserId, toUserId);
    }

    /** Delegates authoritative relation membership lookup. */
    @Override
    public boolean isFollowing(long fromUserId, long toUserId) {
        return queryService.isFollowing(fromUserId, toUserId);
    }

    /** Delegates offset-based following lookup. */
    @Override
    public List<Long> following(long userId, int limit, int offset) {
        return queryService.following(userId, limit, offset);
    }

    /** Delegates offset-based follower lookup. */
    @Override
    public List<Long> followers(long userId, int limit, int offset) {
        return queryService.followers(userId, limit, offset);
    }

    /** Delegates bidirectional relation status lookup. */
    @Override
    public Map<String, Boolean> relationStatus(
            long userId,
            long otherUserId) {
        return queryService.relationStatus(userId, otherUserId);
    }

    /** Delegates cursor-based following lookup. */
    @Override
    public List<Long> followingCursor(
            long userId,
            int limit,
            Long cursor) {
        return queryService.followingCursor(userId, limit, cursor);
    }

    /** Delegates cursor-based follower lookup. */
    @Override
    public List<Long> followersCursor(
            long userId,
            int limit,
            Long cursor) {
        return queryService.followersCursor(userId, limit, cursor);
    }

    /** Delegates following profile-page assembly. */
    @Override
    public PageResponse<ProfileResponse> followingProfilesPage(
            long userId,
            int size,
            String cursor,
            Integer legacyOffset,
            Long legacyCursorMs) {
        return queryService.followingProfilesPage(
                userId, size, cursor, legacyOffset, legacyCursorMs);
    }

    /** Delegates follower profile-page assembly. */
    @Override
    public PageResponse<ProfileResponse> followersProfilesPage(
            long userId,
            int size,
            String cursor,
            Integer legacyOffset,
            Long legacyCursorMs) {
        return queryService.followersProfilesPage(
                userId, size, cursor, legacyOffset, legacyCursorMs);
    }
}
