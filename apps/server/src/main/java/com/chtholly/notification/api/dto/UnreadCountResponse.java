package com.chtholly.notification.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 未读通知数量。 */
@Schema(description = "未读通知数")
public record UnreadCountResponse(
        @Schema(description = "未读数量") long unreadCount
) {}
