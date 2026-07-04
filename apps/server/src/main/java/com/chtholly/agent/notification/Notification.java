package com.chtholly.agent.notification;

import java.time.Instant;

/**
 * Proactive notification produced by Chtholly.
 *
 * @param type      notification type
 * @param message   user-facing message
 * @param timestamp creation time
 * @param channel   target in-site surface
 */
public record Notification(
        String type,
        String message,
        Instant timestamp,
        NotificationChannel channel
) {
    public Notification(String type, String message, NotificationChannel channel) {
        this(type, message, null, channel);
    }
}
