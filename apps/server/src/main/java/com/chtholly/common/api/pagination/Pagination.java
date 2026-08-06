package com.chtholly.common.api.pagination;

/**
 * 分页参数规范化工具。
 */
public final class Pagination {

    public static final int DEFAULT_SIZE = 20;
    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 50;
    /** Largest page whose offset fits in a signed integer at {@link #MAX_SIZE}. */
    public static final int MAX_PAGE = Integer.MAX_VALUE / MAX_SIZE + 1;

    private Pagination() {
    }

    public static int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(Math.max(size, MIN_SIZE), MAX_SIZE);
    }

    public static int normalizePage(int page) {
        return Math.max(page, 1);
    }

    public static int offset(int page, int size) {
        return (normalizePage(page) - 1) * clampSize(size);
    }
}
