package com.chtholly.comment.api;

import com.chtholly.common.ratelimit.RateLimit;
import com.chtholly.common.ratelimit.RateLimitDimension;
import com.chtholly.auth.token.JwtService;
import com.chtholly.comment.api.dto.CommentListResponse;
import com.chtholly.comment.api.dto.CommentResponse;
import com.chtholly.comment.api.dto.CreateCommentRequest;
import com.chtholly.comment.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * REST API for two-level nested comments on posts.
 */
@Tag(name = "评论", description = "评论创建、列表、删除")
@RestController
@RequestMapping("/api/v1/posts/{postId}/comments")
@Validated
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final JwtService jwtService;

    /**
     * Lists top-level comments with nested replies, paginated.
     *
     * @param postId post snowflake ID
     * @param page 1-based page number
     * @param size items per page (max 50)
     * @param jwt optional JWT for viewer-specific fields (may be null)
     * @return paginated comment tree
     */
    @Operation(summary = "评论列表（树形分页）")
    @GetMapping
    public CommentListResponse list(@PathVariable("postId") long postId,
                                    @RequestParam(defaultValue = "1") @Min(1) int page,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
                                    @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        return commentService.listByPost(postId, userId, page, size);
    }

    /**
     * Creates a top-level comment or a reply to an existing comment.
     *
     * @param postId post snowflake ID
     * @param request comment body and optional parent comment ID
     * @param jwt authenticated user JWT
     * @return created comment payload
     */
    @Operation(summary = "创建评论或回复")
    @RateLimit(key = "comments:create", maxRequests = 10, windowSeconds = 60, dimension = RateLimitDimension.USER)
    @PostMapping
    public CommentResponse create(@PathVariable("postId") long postId,
                                  @Valid @RequestBody CreateCommentRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return commentService.create(postId, userId, request);
    }

    /**
     * Soft-deletes a comment owned by the authenticated user.
     *
     * @param postId post snowflake ID
     * @param commentId comment snowflake ID
     * @param jwt authenticated user JWT
     */
    @Operation(summary = "删除评论（软删除）")
    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("postId") long postId,
                       @PathVariable("commentId") long commentId,
                       @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        commentService.delete(postId, commentId, userId);
    }
}
