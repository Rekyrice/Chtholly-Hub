package com.chtholly.notification.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.notification.api.dto.NotificationListResponse;
import com.chtholly.notification.api.dto.UnreadCountResponse;
import com.chtholly.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the authenticated user's in-app notifications.
 */
@Tag(name = "通知", description = "通知列表、已读标记")
@RestController
@RequestMapping("/api/v1/notifications")
@Validated
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final JwtService jwtService;

    /**
     * Returns a paginated list of notifications for the current user.
     *
     * @param jwt authenticated user JWT
     * @param page 1-based page number
     * @param size items per page
     * @return notification list page
     */
    @Operation(summary = "通知分页列表")
    @GetMapping
    public NotificationListResponse list(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(value = "page", defaultValue = "1") int page,
                                         @RequestParam(value = "size", defaultValue = "20") int size) {
        long userId = jwtService.extractUserId(jwt);
        return notificationService.list(userId, page, size);
    }

    /**
     * Returns the unread notification count for the current user.
     *
     * @param jwt authenticated user JWT
     * @return unread count payload
     */
    @Operation(summary = "未读通知数量")
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return notificationService.unreadCount(userId);
    }

    /**
     * Marks a single notification as read.
     *
     * @param jwt authenticated user JWT
     * @param id notification snowflake ID
     */
    @Operation(summary = "标记单条通知已读")
    @PatchMapping("/{id}/read")
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        long userId = jwtService.extractUserId(jwt);
        notificationService.markRead(userId, id);
    }

    /**
     * Marks all notifications as read for the current user.
     *
     * @param jwt authenticated user JWT
     */
    @Operation(summary = "全部标记已读")
    @PostMapping("/read-all")
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        notificationService.markAllRead(userId);
    }
}
