package com.chtholly.common.api.pagination;

/**
 * Offset 分页请求参数。
 */
public record PageRequest(int page, int size) {

    public PageRequest {
        page = Pagination.normalizePage(page);
        size = Pagination.clampSize(size);
    }

    public static PageRequest of(int page, int size) {
        return new PageRequest(page, size);
    }

    public int offset() {
        return Pagination.offset(page, size);
    }
}
