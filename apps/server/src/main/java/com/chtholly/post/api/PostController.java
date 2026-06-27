package com.chtholly.post.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.post.api.dto.PostContentConfirmRequest;
import com.chtholly.post.api.dto.PostDraftCreateResponse;
import com.chtholly.post.api.dto.PostPatchRequest;
import com.chtholly.post.api.dto.PostTopPatchRequest;
import com.chtholly.post.api.dto.PostVisibilityPatchRequest;
import com.chtholly.post.api.dto.FeedPageResponse;
import com.chtholly.post.service.PostService;
import com.chtholly.post.service.PostFeedService;
import com.chtholly.post.api.dto.PostDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "文章", description = "文章 CRUD、Feed、发布")
@RestController
@RequestMapping("/api/v1/posts")
@Validated
@RequiredArgsConstructor
public class PostController {

    private final PostService service;
    private final PostFeedService feedService;
    private final JwtService jwtService;

    @Operation(summary = "创建草稿")
    @PostMapping("/drafts")
    public PostDraftCreateResponse createDraft(@AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        long id = service.createDraft(userId);
        return new PostDraftCreateResponse(String.valueOf(id));
    }

    @Operation(summary = "确认内容上传")
    @PostMapping("/{id}/content/confirm")
    public ResponseEntity<Void> confirmContent(@PathVariable("id") long id,
                                               @Valid @RequestBody PostContentConfirmRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.confirmContent(userId, id, request.objectKey(), request.etag(), request.size(), request.sha256());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "更新帖子元数据")
    @PatchMapping("/{id}")
    public ResponseEntity<Void> patchMetadata(@PathVariable("id") long id,
                                              @Valid @RequestBody PostPatchRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.updateMetadata(userId, id, request.title(), request.tagId(), request.tags(), request.imgUrls(), request.visible(), request.isTop(), request.description());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "发布帖子")
    @PostMapping("/{id}/publish")
    public ResponseEntity<Void> publish(@PathVariable("id") long id,
                                        @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.publish(userId, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "设置置顶")
    @PatchMapping("/{id}/top")
    public ResponseEntity<Void> patchTop(@PathVariable("id") long id,
                                         @Valid @RequestBody PostTopPatchRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.updateTop(userId, id, request.isTop());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "设置可见性")
    @PatchMapping("/{id}/visibility")
    public ResponseEntity<Void> patchVisibility(@PathVariable("id") long id,
                                                @Valid @RequestBody PostVisibilityPatchRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.updateVisibility(userId, id, request.visible());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "删除帖子（软删除）")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id,
                                       @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "公开 Feed 列表")
    @GetMapping("/feed")
    public FeedPageResponse feed(@RequestParam(value = "page", defaultValue = "1") int page,
                                 @RequestParam(value = "size", defaultValue = "20") int size,
                                 @RequestParam(value = "ownerId", required = false) Long ownerId,
                                 @RequestParam(value = "tag", required = false) String tag,
                                 @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        return feedService.getPublicFeed(page, size, ownerId, tag, userId);
    }

    @Operation(summary = "我的已发布帖子")
    @GetMapping("/mine")
    public FeedPageResponse mine(@RequestParam(value = "page", defaultValue = "1") int page,
                                 @RequestParam(value = "size", defaultValue = "20") int size,
                                 @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return feedService.getMyPublished(userId, page, size);
    }

    @Operation(summary = "帖子详情（按 ID）")
    @GetMapping("/detail/{id}")
    public PostDetailResponse detail(@PathVariable("id") long id,
                                         @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        return service.getDetail(id, userId);
    }

    @Operation(summary = "帖子详情（按 slug）")
    @GetMapping("/detail/by-slug/{slug}")
    public PostDetailResponse detailBySlug(@PathVariable("slug") String slug,
                                         @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        return service.getDetailBySlug(slug, userId);
    }
}
