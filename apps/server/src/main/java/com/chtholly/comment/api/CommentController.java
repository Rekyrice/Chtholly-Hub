package com.chtholly.comment.api;

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

/** 帖子评论 API：两层嵌套。 */
@Tag(name = "评论", description = "评论创建、列表、删除")
@RestController
@RequestMapping("/api/v1/posts/{postId}/comments")
@Validated
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final JwtService jwtService;

    @Operation(summary = "评论列表（树形分页）")
    @GetMapping
    public CommentListResponse list(@PathVariable("postId") long postId,
                                    @RequestParam(defaultValue = "1") @Min(1) int page,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
                                    @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        return commentService.listByPost(postId, userId, page, size);
    }

    @Operation(summary = "创建评论或回复")
    @PostMapping
    public CommentResponse create(@PathVariable("postId") long postId,
                                  @Valid @RequestBody CreateCommentRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return commentService.create(postId, userId, request);
    }

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
