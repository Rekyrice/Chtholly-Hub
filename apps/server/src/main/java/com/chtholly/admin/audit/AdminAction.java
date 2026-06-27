package com.chtholly.admin.audit;

/**
 * 管理员操作类型。
 */
public enum AdminAction {
    BAN_USER,
    UNBAN_USER,
    UPDATE_USER_ROLE,
    HIDE_POST,
    DELETE_POST,
    DELETE_COMMENT
}
