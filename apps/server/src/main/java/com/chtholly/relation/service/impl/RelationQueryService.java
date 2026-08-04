package com.chtholly.relation.service.impl;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.common.api.pagination.Pagination;
import com.chtholly.common.util.SensitiveDataUtil;
import com.chtholly.profile.api.dto.ProfileResponse;
import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.mapper.RelationMapper.RelationPageRow;
import com.chtholly.relation.util.RelationCursor;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinates relation status, pagination, and ordered profile assembly queries. */
@Service
public class RelationQueryService {

    private final RelationMapper relationMapper;
    private final RelationProjectionCache projectionCache;
    private final UserMapper userMapper;

    /** Creates the relation query application service. */
    public RelationQueryService(
            RelationMapper relationMapper,
            RelationProjectionCache projectionCache,
            UserMapper userMapper) {
        this.relationMapper = relationMapper;
        this.projectionCache = projectionCache;
        this.userMapper = userMapper;
    }

    /** Returns whether a directed relation exists in the authoritative store. */
    public boolean isFollowing(long fromUserId, long toUserId) {
        return relationMapper.existsFollowing(fromUserId, toUserId) > 0;
    }

    /** Returns the stable following, followedBy, and mutual status map. */
    public Map<String, Boolean> relationStatus(
            long userId,
            long otherUserId) {
        boolean following = isFollowing(userId, otherUserId);
        boolean followedBy = isFollowing(otherUserId, userId);
        Map<String, Boolean> status = new LinkedHashMap<>();
        status.put("following", following);
        status.put("followedBy", followedBy);
        status.put("mutual", following && followedBy);
        return status;
    }

    /** Reads following identifiers using offset pagination. */
    public List<Long> following(long userId, int limit, int offset) {
        return projectionCache.following(userId, limit, offset);
    }

    /** Reads follower identifiers using offset pagination. */
    public List<Long> followers(long userId, int limit, int offset) {
        return projectionCache.followers(userId, limit, offset);
    }

    /** Reads following identifiers using the legacy millisecond cursor. */
    public List<Long> followingCursor(
            long userId,
            int limit,
            Long cursor) {
        return projectionCache.followingCursor(userId, limit, cursor);
    }

    /** Reads follower identifiers using the legacy millisecond cursor. */
    public List<Long> followersCursor(
            long userId,
            int limit,
            Long cursor) {
        return projectionCache.followersCursor(userId, limit, cursor);
    }

    /** Returns a stable profile page for following relations. */
    public PageResponse<ProfileResponse> followingProfilesPage(
            long userId,
            int size,
            String cursor,
            Integer legacyOffset,
            Long legacyCursorMs) {
        return profilesPage(
                userId,
                size,
                cursor,
                legacyOffset,
                legacyCursorMs,
                true);
    }

    /** Returns a stable profile page for follower relations. */
    public PageResponse<ProfileResponse> followersProfilesPage(
            long userId,
            int size,
            String cursor,
            Integer legacyOffset,
            Long legacyCursorMs) {
        return profilesPage(
                userId,
                size,
                cursor,
                legacyOffset,
                legacyCursorMs,
                false);
    }

    private PageResponse<ProfileResponse> profilesPage(
            long userId,
            int size,
            String cursor,
            Integer legacyOffset,
            Long legacyCursorMs,
            boolean following) {
        int safeSize = Pagination.clampSize(size);
        int fetch = safeSize + 1;
        if (legacyOffset != null
                && legacyOffset > 0
                && !hasCursorInput(cursor, legacyCursorMs)) {
            int offset = Math.max(legacyOffset, 0);
            List<Long> ids = following
                    ? following(userId, fetch, offset)
                    : followers(userId, fetch, offset);
            boolean hasMore = ids.size() > safeSize;
            if (hasMore) {
                ids = ids.subList(0, safeSize);
            }
            int page = offset / safeSize + 1;
            return PageResponse.offset(
                    toProfiles(ids), page, safeSize, 0L, hasMore);
        }

        RelationCursor.RelationCursorPoint cursorPoint = resolveCursor(
                cursor, legacyCursorMs);
        if (cursorPoint == null || cursorPoint.userId() != null) {
            return compositeProfilesPage(
                    userId, safeSize, cursorPoint, following);
        }

        Long cursorMillis = cursorPoint.createdAtMillis();
        List<Long> ids = following
                ? followingCursor(userId, fetch, cursorMillis)
                : followersCursor(userId, fetch, cursorMillis);
        boolean hasMore = ids.size() > safeSize;
        if (hasMore) {
            ids = ids.subList(0, safeSize);
        }
        String nextCursor = null;
        if (hasMore && !ids.isEmpty()) {
            long lastId = ids.getLast();
            Double score = projectionCache.score(
                    following, userId, lastId);
            if (score != null) {
                nextCursor = RelationCursor.encode(
                        score.longValue(), lastId);
            }
        }
        return PageResponse.cursor(
                toProfiles(ids), safeSize, hasMore, nextCursor);
    }

    private PageResponse<ProfileResponse> compositeProfilesPage(
            long userId,
            int safeSize,
            RelationCursor.RelationCursorPoint cursorPoint,
            boolean following) {
        Date cursorCreatedAt = cursorPoint == null
                ? null
                : new Date(cursorPoint.createdAtMillis());
        Long cursorRelatedUserId = cursorPoint == null
                ? null
                : cursorPoint.userId();
        int fetch = safeSize + 1;
        List<RelationPageRow> rows = following
                ? relationMapper.listFollowingPage(
                        userId, cursorCreatedAt, cursorRelatedUserId, fetch)
                : relationMapper.listFollowerPage(
                        userId, cursorCreatedAt, cursorRelatedUserId, fetch);
        if (rows == null) {
            rows = List.of();
        }
        boolean hasMore = rows.size() > safeSize;
        if (hasMore) {
            rows = rows.subList(0, safeSize);
        }
        List<Long> ids = rows.stream()
                .map(RelationPageRow::relatedUserId)
                .toList();
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            RelationPageRow last = rows.getLast();
            nextCursor = RelationCursor.encode(
                    last.createdAt().getTime(), last.relatedUserId());
        }
        return PageResponse.cursor(
                toProfiles(ids), safeSize, hasMore, nextCursor);
    }

    private List<ProfileResponse> toProfiles(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<User> users = userMapper.listByIds(ids);
        Map<Long, User> byId = new LinkedHashMap<>(users.size());
        for (User user : users) {
            byId.put(user.getId(), user);
        }
        List<ProfileResponse> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            User user = byId.get(id);
            if (user == null) {
                continue;
            }
            result.add(new ProfileResponse(
                    user.getId(),
                    user.getNickname(),
                    user.getAvatar(),
                    user.getBio(),
                    user.getHandle(),
                    user.getGender(),
                    user.getBirthday(),
                    user.getSchool(),
                    SensitiveDataUtil.maskPhone(user.getPhone()),
                    SensitiveDataUtil.maskEmail(user.getEmail()),
                    user.getTagsJson()));
        }
        return result;
    }

    private static boolean hasCursorInput(
            String cursor,
            Long legacyCursorMs) {
        return (cursor != null && !cursor.isBlank())
                || legacyCursorMs != null;
    }

    private static RelationCursor.RelationCursorPoint resolveCursor(
            String cursor,
            Long legacyCursorMs) {
        if (cursor != null && !cursor.isBlank()) {
            return RelationCursor.require(cursor);
        }
        return legacyCursorMs == null
                ? null
                : new RelationCursor.RelationCursorPoint(
                        legacyCursorMs, null);
    }
}
