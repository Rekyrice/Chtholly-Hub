package com.chtholly.notification.api;

import com.chtholly.common.web.HttpCacheHelper;
import com.chtholly.auth.token.JwtService;
import com.chtholly.notification.api.dto.NotificationListResponse;
import com.chtholly.notification.api.dto.UnreadCountResponse;
import com.chtholly.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<NotificationListResponse> list(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(value = "page", defaultValue = "1") @Min(1) int page,
                                         @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(50) int size) {
        long userId = jwtService.extractUserId(jwt);
        NotificationListResponse body = notificationService.list(userId, page, size);
        return HttpCacheHelper.okPrivate(body);
    }

    /**
     * Returns the unread notification count for the current user.
     *
     * @param jwt authenticated user JWT
     * @return unread count payload
     */
    @Operation(summary = "未读通知数量")
    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        UnreadCountResponse body = notificationService.unreadCount(userId);
        return HttpCacheHelper.okPrivate(body);
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
