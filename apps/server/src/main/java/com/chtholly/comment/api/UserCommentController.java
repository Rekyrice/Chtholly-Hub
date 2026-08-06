package com.chtholly.comment.api;

import com.chtholly.comment.api.dto.UserCommentActivityResponse;
import com.chtholly.comment.service.CommentService;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.common.api.pagination.Pagination;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provides anonymous read access to a user's public comment activity.
 */
@Tag(name = "User comments", description = "Public user comment activity")
@RestController
@RequestMapping("/api/v1/users/{userId}/comments")
@Validated
@RequiredArgsConstructor
public class UserCommentController {

    private final CommentService commentService;

    /**
     * Lists a user's comments on published public posts.
     *
     * @param userId comment author ID
     * @param page page number (1-based, max {@link Pagination#MAX_PAGE})
     * @param size items per page (max 50)
     * @return paginated public comment activity
     */
    @Operation(summary = "List public user comment activity")
    @GetMapping
    public PageResponse<UserCommentActivityResponse> list(
            @PathVariable("userId") @Positive long userId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(Pagination.MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return commentService.listByUser(userId, page, size);
    }
}
