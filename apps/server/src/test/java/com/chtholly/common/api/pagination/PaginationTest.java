package com.chtholly.common.api.pagination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaginationTest {

    @Test
    void clampSize_boundsAndDefault() {
        assertEquals(20, Pagination.clampSize(0));
        assertEquals(1, Pagination.clampSize(1));
        assertEquals(50, Pagination.clampSize(50));
        assertEquals(50, Pagination.clampSize(200));
    }

    @Test
    void pageRequest_normalizesValues() {
        PageRequest req = PageRequest.of(0, 100);
        assertEquals(1, req.page());
        assertEquals(50, req.size());
        assertEquals(0, req.offset());
    }

    @Test
    void pageResponse_offsetHasMore() {
        PageResponse<String> page = PageResponse.offset(java.util.List.of("a"), 1, 20, 25);
        assertEquals(true, page.hasMore());
        assertEquals(null, page.nextCursor());
    }
}
