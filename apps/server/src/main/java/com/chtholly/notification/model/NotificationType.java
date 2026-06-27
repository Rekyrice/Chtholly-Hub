package com.chtholly.notification.model;

/** 通知类型。 */
public enum NotificationType {
    /** 有人评论了你的帖子 */
    COMMENT_POST,
    /** 有人回复了你的评论 */
    COMMENT_REPLY,
    /** 有人赞了你的帖子 */
    LIKE_POST,
    /** 有人关注了你 */
    FOLLOW,
    /** 未知类型（兼容旧数据或新类型未升级） */
    UNKNOWN
}
