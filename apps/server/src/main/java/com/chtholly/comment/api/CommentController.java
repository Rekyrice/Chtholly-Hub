package com.chtholly.comment.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.comment.api.dto.CommentListResponse;
import com.chtholly.comment.api.dto.CommentResponse;
import com.chtholly.comment.api.dto.CreateCommentRequest;
import com.chtholly.comment.service.CommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

/** 帖子评论 API：两层嵌套。 */
@RestController
@RequestMapping("/api/v1/posts/{postId}/comments")
@Validated
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final JwtService jwtService;

    @GetMapping
    public CommentListResponse list(@PathVariable("postId") long postId,
                                    @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        return commentService.listByPost(postId, userId);
    }

    @PostMapping
    public CommentResponse create(@PathVariable("postId") long postId,
                                @Valid @RequestBody CreateCommentRequest request,
                                @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return commentService.create(postId, userId, request);
    }
}
