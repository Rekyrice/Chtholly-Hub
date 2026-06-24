package com.chtholly.post.util;

import java.util.function.Function;

/**
 * 帖子 URL slug 生成与去重工具。
 */
public final class SlugUtils {

    private SlugUtils() {}

    /**
     * 从标题生成 slug 基础值（小写、非字母数字替换为连字符）。
     */
    public static String fromTitle(String title) {
        if (title == null || title.isBlank()) {
            return "post-" + System.currentTimeMillis();
        }
        String base = title.trim().toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-|-$", "");
        if (base.isBlank()) {
            base = "post";
        }
        return base.length() > 100 ? base.substring(0, 100) : base;
    }

    /**
     * 确保 slug 唯一；若与已有帖子冲突则追加 -2、-3… 后缀。
     */
    public static String ensureUnique(String base, Long existingId, Function<String, Long> slugToId) {
        String candidate = base;
        int n = 1;
        while (true) {
            Long found = slugToId.apply(candidate);
            if (found == null || found.equals(existingId)) {
                return candidate;
            }
            candidate = base + "-" + (++n);
        }
    }
}
