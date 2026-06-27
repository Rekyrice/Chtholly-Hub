package com.chtholly.notification.model;

import lombok.Data;

import java.time.Instant;

@Data
public class NotificationRow {
    private Long id;
    private Long userId;
    private String type;
    private String payload;
    private Instant readAt;
    private Instant createdAt;
}
