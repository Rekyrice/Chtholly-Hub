package com.chtholly.relation.api;

import com.chtholly.relation.service.RelationCounterQueryService;
import com.chtholly.relation.service.RelationService;
import com.chtholly.auth.token.JwtService;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.profile.api.dto.ProfileResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for follow relationships, profile lists, and Redis-backed user counters.
 */
@RestController
@RequestMapping("/api/v1/relation")
public class RelationController {
    private final RelationService relationService;
    private final JwtService jwtService;
    private final RelationCounterQueryService counterQueryService;

    /**
     * Creates the HTTP adapter for relationship commands, profile lists, and counter queries.
     *
     * @param relationService relationship command and profile query service
     * @param jwtService authenticated user identity extractor
     * @param counterQueryService user counter projection query service
     */
    public RelationController(
            RelationService relationService,
            JwtService jwtService,
            RelationCounterQueryService counterQueryService) {
        this.relationService = relationService;
        this.jwtService = jwtService;
        this.counterQueryService = counterQueryService;
    }

    /**
     * Follows another user.
     *
     * @param toUserId target user snowflake ID
     * @param jwt authenticated user JWT
     * @return {@code true} if the follow state changed
     */
    @PostMapping("/follow")
    public boolean follow(@RequestParam("toUserId") long toUserId, @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        return relationService.follow(uid, toUserId);
    }

    /**
     * Unfollows another user.
     *
     * @param toUserId target user snowflake ID
     * @param jwt authenticated user JWT
     * @return {@code true} if the follow state changed
     */
    @PostMapping("/unfollow")
    public boolean unfollow(@RequestParam("toUserId") long toUserId, @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        return relationService.unfollow(uid, toUserId);
    }

    /**
     * Returns mutual follow status between the caller and a target user.
     *
     * @param toUserId target user snowflake ID
     * @param jwt authenticated user JWT
     * @return map with {@code following}, {@code followedBy}, and {@code mutual} flags
     */
    @GetMapping("/status")
    public Map<String, Boolean> status(@RequestParam("toUserId") long toUserId, @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        return relationService.relationStatus(uid, toUserId);
    }

    /**
     * Lists users the given user follows, with offset or cursor pagination.
     *
     * @param userId subject user snowflake ID
     * @param limit maximum profiles to return (clamped to 100)
     * @param offset zero-based offset when {@code cursor} is absent
     * @param cursor optional millisecond timestamp cursor
     * @return following user profiles
     */
    @GetMapping("/following")
    public PageResponse<ProfileResponse> following(@RequestParam("userId") long userId,
                                @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(50) int size,
                                @RequestParam(value = "cursor", required = false) String cursor,
                                @RequestParam(value = "limit", required = false) Integer limit,
                                @RequestParam(value = "offset", required = false) Integer offset,
                                @RequestParam(value = "cursorMs", required = false) Long cursorMs) {
        int resolvedSize = limit != null ? limit : size;
        return relationService.followingProfilesPage(userId, resolvedSize, cursor, offset, cursorMs);
    }

    /**
     * Lists followers of the given user, with cursor pagination.
     *
     * @param userId subject user snowflake ID
     * @param size items per page (1–50)
     * @param cursor optional Base64URL cursor
     * @return follower user profiles page
     */
    @GetMapping("/followers")
    public PageResponse<ProfileResponse> followers(@RequestParam("userId") long userId,
                                          @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(50) int size,
                                          @RequestParam(value = "cursor", required = false) String cursor,
                                          @RequestParam(value = "limit", required = false) Integer limit,
                                          @RequestParam(value = "offset", required = false) Integer offset,
                                          @RequestParam(value = "cursorMs", required = false) Long cursorMs) {
        int resolvedSize = limit != null ? limit : size;
        return relationService.followersProfilesPage(userId, resolvedSize, cursor, offset, cursorMs);
    }

    /**
     * Reads aggregated user counters from Redis SDS, with sampled consistency checks.
     *
     * @param userId subject user snowflake ID
     * @return map of followings, followers, posts, likedPosts, and favedPosts counts
     */
    @GetMapping("/counter")
    public Map<String, Long> counter(@RequestParam("userId") long userId) {
        return counterQueryService.getCounters(userId);
    }
}
