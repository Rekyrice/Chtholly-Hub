package com.chtholly.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpCacheHelperTest {

    @Test
    void hashEtag_isStableAndChangesWithInput() {
        String a = HttpCacheHelper.hashEtag("published", "2", "2026-01-01T00:00:00Z");
        String b = HttpCacheHelper.hashEtag("published", "2", "2026-01-01T00:00:00Z");
        String c = HttpCacheHelper.hashEtag("draft", "2", "2026-01-01T00:00:00Z");

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(32, a.length());
    }

    @Test
    void matchesIfNoneMatch_handlesQuotesAndWildcard() {
        String etag = HttpCacheHelper.hashEtag("x");
        assertTrue(HttpCacheHelper.matchesIfNoneMatch("\"" + etag + "\"", etag));
        assertTrue(HttpCacheHelper.matchesIfNoneMatch("W/\"" + etag + "\"", etag));
        assertTrue(HttpCacheHelper.matchesIfNoneMatch("*", etag));
        assertFalse(HttpCacheHelper.matchesIfNoneMatch("\"other\"", etag));
    }

    @Test
    void conditionalPublic_returns304WithoutBody() {
        String etag = HttpCacheHelper.hashEtag("feed", "1", "20");
        ResponseEntity<String> notModified = HttpCacheHelper.conditionalPublic("payload", etag, etag);
        assertEquals(HttpStatus.NOT_MODIFIED, notModified.getStatusCode());
        assertNull(notModified.getBody());
        assertEquals("\"" + etag + "\"", notModified.getHeaders().getETag());
    }

    @Test
    void conditionalPublic_returns200WithBodyWhenEtagDiffers() {
        String etag = HttpCacheHelper.hashEtag("feed", "1", "20");
        ResponseEntity<String> ok = HttpCacheHelper.conditionalPublic("payload", etag, "stale");
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertEquals("payload", ok.getBody());
    }
}
