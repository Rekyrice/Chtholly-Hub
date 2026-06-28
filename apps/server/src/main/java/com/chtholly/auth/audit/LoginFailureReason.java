package com.chtholly.auth.audit;

/** 登录失败原因，写入 login_logs.failure_reason。 */
public enum LoginFailureReason {
    WRONG_PASSWORD,
    ACCOUNT_LOCKED,
    ACCOUNT_NOT_FOUND
}
