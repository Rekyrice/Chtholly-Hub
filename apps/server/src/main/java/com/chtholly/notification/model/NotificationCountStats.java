package com.chtholly.notification.model;

import lombok.Data;

/** 通知数量统计（总数 + 未读数）。 */
@Data
public class NotificationCountStats {
    private long total;
    private long unread;
}
