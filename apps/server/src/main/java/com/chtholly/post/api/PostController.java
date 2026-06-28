package com.chtholly.post.api;

import com.chtholly.common.web.HttpCacheHelper;
import com.chtholly.common.ratelimit.RateLimit;
import com.chtholly.common.ratelimit.RateLimitDimension;
import com.chtholly.auth.token.JwtService;
import com.chtholly.post.api.dto.PostContentConfirmRequest;
import com.chtholly.post.api.dto.PostDraftCreateResponse;
import com.chtholly.post.api.dto.PostPatchRequest;
import com.chtholly.post.api.dto.PostTopPatchRequest;
import com.chtholly.post.api.dto.PostVisibilityPatchRequest;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.service.PostService;
import com.chtholly.post.service.PostFeedService;
import com.chtholly.post.api.dto.PostDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for post lifecycle: drafts, metadata, publishing, feeds, and detail views.
 */
@Tag(name = "文章", description = "文章 CRUD、Feed、发布")
@RestController
@RequestMapping("/api/v1/posts")
@Validated
@RequiredArgsConstructor
public class PostController {

    private final PostService service;
    private final PostFeedService feedService;
    private final JwtService jwtService;

    /**
     * Creates an empty draft owned by the authenticated user.
     *
     * @param jwt authenticated user JWT
     * @return new draft post ID
     */
    @Operation(summary = "创建草稿")
    @RateLimit(key = "posts:drafts", maxRequests = 10, windowSeconds = 60, dimension = RateLimitDimension.USER)
    @PostMapping("/drafts")
    public PostDraftCreateResponse createDraft(@AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        long id = service.createDraft(userId);
        return new PostDraftCreateResponse(String.valueOf(id));
    }

    /**
     * Confirms OSS upload completion and binds content metadata to the draft.
     *
     * @param id post snowflake ID
     * @param request object key, etag, size, and checksum from the upload
     * @param jwt authenticated user JWT
     * @return HTTP 204 on success
     */
    @Operation(summary = "确认内容上传")
    @PostMapping("/{id}/content/confirm")
    public ResponseEntity<Void> confirmContent(@PathVariable("id") long id,
                                               @Valid @RequestBody PostContentConfirmRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.confirmContent(userId, id, request.objectKey(), request.etag(), request.size(), request.sha256());
        return ResponseEntity.noContent().build();
    }

    /**
     * Partially updates post metadata (title, tags, images, visibility, etc.).
     *
     * @param id post snowflake ID
     * @param request fields to patch
     * @param jwt authenticated user JWT
     * @return HTTP 204 on success
     */
    @Operation(summary = "更新帖子元数据")
    @PatchMapping("/{id}")
    public ResponseEntity<Void> patchMetadata(@PathVariable("id") long id,
                                              @Valid @RequestBody PostPatchRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.updateMetadata(userId, id, request.title(), request.tagId(), request.tags(), request.imgUrls(), request.visible(), request.isTop(), request.description());
        return ResponseEntity.noContent().build();
    }

    /**
     * Publishes a draft post, making it visible per its visibility setting.
     *
     * @param id post snowflake ID
     * @param jwt authenticated user JWT
     * @return HTTP 204 on success
     */
    @Operation(summary = "发布帖子")
    @RateLimit(key = "posts:publish", maxRequests = 5, windowSeconds = 60, dimension = RateLimitDimension.USER)
    @PostMapping("/{id}/publish")
    public ResponseEntity<Void> publish(@PathVariable("id") long id,
                                        @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.publish(userId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sets or clears the pinned (top) flag for a post.
     *
     * @param id post snowflake ID
     * @param request desired pinned state
     * @param jwt authenticated user JWT
     * @return HTTP 204 on success
     */
    @Operation(summary = "设置置顶")
    @PatchMapping("/{id}/top")
    public ResponseEntity<Void> patchTop(@PathVariable("id") long id,
                                         @Valid @RequestBody PostTopPatchRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.updateTop(userId, id, request.isTop());
        return ResponseEntity.noContent().build();
    }

    /**
     * Updates post visibility (public, private, etc.).
     *
     * @param id post snowflake ID
     * @param request desired visibility value
     * @param jwt authenticated user JWT
     * @return HTTP 204 on success
     */
    @Operation(summary = "设置可见性")
    @PatchMapping("/{id}/visibility")
    public ResponseEntity<Void> patchVisibility(@PathVariable("id") long id,
                                                @Valid @RequestBody PostVisibilityPatchRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.updateVisibility(userId, id, request.visible());
        return ResponseEntity.noContent().build();
    }

    /**
     * Soft-deletes a post owned by the authenticated user.
     *
     * @param id post snowflake ID
     * @param jwt authenticated user JWT
     * @return HTTP 204 on success
     */
    @Operation(summary = "删除帖子（软删除）")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id,
                                       @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns a paginated public feed with optional owner and tag filters.
     *
     * @param page 1-based page number
     * @param size items per page
     * @param ownerId optional author filter
     * @param tag optional tag slug filter
     * @param jwt optional JWT for personalized fields (may be null)
     * @return feed page with post summaries
     */
    @Operation(summary = "公开 Feed 列表")
    @GetMapping("/feed")
    public ResponseEntity<PageResponse<FeedItemResponse>> feed(@RequestParam(value = "page", required = false) Integer page,
                                 @RequestParam(value = "cursor", required = false) String cursor,
                                 @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(50) int size,
                                 @RequestParam(value = "ownerId", required = false) Long ownerId,
                                 @RequestParam(value = "tag", required = false) String tag,
                                 @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
                                 @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        PageResponse<FeedItemResponse> body = feedService.getPublicFeed(page, cursor, size, ownerId, tag, userId);
        String pageKey = feedService.publicFeedPageKey(page, cursor, size, ownerId, tag);
        long hourSlot = System.currentTimeMillis() / 3600000L;
        int itemCount = body.items() != null ? body.items().size() : 0;
        String etag = HttpCacheHelper.hashEtag(pageKey, String.valueOf(hourSlot), String.valueOf(itemCount));
        return HttpCacheHelper.conditionalPublic(body, etag, ifNoneMatch);
    }

    /**
     * Lists published posts for the authenticated user.
     *
     * @param page 1-based page number
     * @param size items per page
     * @param jwt authenticated user JWT
     * @return paginated feed of the caller's posts
     */
    @Operation(summary = "我的已发布帖子")
    @GetMapping("/mine")
    public PageResponse<FeedItemResponse> mine(@RequestParam(value = "page", defaultValue = "1") int page,
                                 @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(50) int size,
                                 @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return feedService.getMyPublished(userId, page, size);
    }

    /**
     * 关注时间线：展示当前用户所关注作者的最新文章（推拉结合）。
     */
    @Operation(summary = "关注 Feed 列表")
    @GetMapping("/feed/following")
    public PageResponse<FeedItemResponse> followingFeed(@RequestParam(value = "page", defaultValue = "1") int page,
                                          @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(50) int size,
                                          @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return feedService.getFollowingFeed(userId, page, size);
    }

    /**
     * Returns full post detail by snowflake ID.
     *
     * @param id post snowflake ID
     * @param jwt optional JWT for personalized fields (may be null)
     * @return post detail payload
     */
    @Operation(summary = "帖子详情（按 ID）")
    @GetMapping("/detail/{id}")
    public ResponseEntity<PostDetailResponse> detail(@PathVariable("id") long id,
                                         @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
                                         @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        String etag = service.computeDetailEtag(id);
        if (HttpCacheHelper.matchesIfNoneMatch(ifNoneMatch, etag)) {
            return HttpCacheHelper.notModifiedPublic(etag);
        }
        PostDetailResponse body = service.getDetail(id, userId);
        return HttpCacheHelper.okPublic(body, etag);
    }

    /**
     * Returns full post detail by URL slug.
     *
     * @param slug post slug
     * @param jwt optional JWT for personalized fields (may be null)
     * @return post detail payload
     */
    @Operation(summary = "帖子详情（按 slug）")
    @GetMapping("/detail/by-slug/{slug}")
    public ResponseEntity<PostDetailResponse> detailBySlug(@PathVariable("slug") String slug,
                                         @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
                                         @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        String etag = service.computeDetailEtagBySlug(slug);
        if (HttpCacheHelper.matchesIfNoneMatch(ifNoneMatch, etag)) {
            return HttpCacheHelper.notModifiedPublic(etag);
        }
        PostDetailResponse body = service.getDetailBySlug(slug, userId);
        return HttpCacheHelper.okPublic(body, etag);
    }
}
