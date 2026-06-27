package com.chtholly.common.util;

/**
 * 敏感个人信息（PII）脱敏工具。
 */
public final class SensitiveDataUtil {

    private SensitiveDataUtil() {
    }

    /**
     * 手机号脱敏：保留前 3 位与后 4 位，如 138****1234。
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String trimmed = phone.trim();
        if (trimmed.length() <= 7) {
            return trimmed;
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    /**
     * 邮箱脱敏：保留首字符与 @ 后域名，如 r***rice@example.com。
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return email;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() == 1) {
            return local.charAt(0) + "***" + domain;
        }
        return local.charAt(0) + "***" + local.substring(1) + domain;
    }
}
