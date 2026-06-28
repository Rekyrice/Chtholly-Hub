package com.chtholly.common.api.pagination;

/**
 * 游标分页请求参数。
 */
public record CursorRequest(String cursor, int size) {

    public CursorRequest {
        size = Pagination.clampSize(size);
    }

    public static CursorRequest of(String cursor, int size) {
        return new CursorRequest(cursor, size);
    }

    public boolean hasCursor() {
        return cursor != null && !cursor.isBlank();
    }
}
