package com.chtholly.common.api.pagination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void maxPageKeepsWorstCaseOffsetWithinIntegerRange() {
        assertEquals(Integer.MAX_VALUE / Pagination.MAX_SIZE + 1, Pagination.MAX_PAGE);

        long maxSafeOffset = (long) (Pagination.MAX_PAGE - 1) * Pagination.MAX_SIZE;
        long nextOffset = (long) Pagination.MAX_PAGE * Pagination.MAX_SIZE;
        assertTrue(maxSafeOffset <= Integer.MAX_VALUE);
        assertTrue(nextOffset > Integer.MAX_VALUE);
        assertEquals((int) maxSafeOffset, Pagination.offset(Pagination.MAX_PAGE, Pagination.MAX_SIZE));
    }

    @Test
    void pageResponse_offsetHasMore() {
        PageResponse<String> page = PageResponse.offset(java.util.List.of("a"), 1, 20, 25);
        assertEquals(true, page.hasMore());
        assertEquals(null, page.nextCursor());
    }
}
