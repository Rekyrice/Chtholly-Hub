package com.chtholly.notification.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.notification.api.dto.NotificationListResponse;
import com.chtholly.notification.api.dto.UnreadCountResponse;
import com.chtholly.notification.service.NotificationService;
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

/** 当前用户通知 API。 */
@RestController
@RequestMapping("/api/v1/notifications")
@Validated
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final JwtService jwtService;

    @GetMapping
    public NotificationListResponse list(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(value = "page", defaultValue = "1") int page,
                                         @RequestParam(value = "size", defaultValue = "20") int size) {
        long userId = jwtService.extractUserId(jwt);
        return notificationService.list(userId, page, size);
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return notificationService.unreadCount(userId);
    }

    @PatchMapping("/{id}/read")
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        long userId = jwtService.extractUserId(jwt);
        notificationService.markRead(userId, id);
    }

    @PostMapping("/read-all")
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        notificationService.markAllRead(userId);
    }
}
