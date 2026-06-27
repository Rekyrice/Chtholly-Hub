package com.chtholly.post.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FeedCursorTest {

    @Test
    void given_publishTimeAndId_when_encodeDecode_then_roundTrip() {
        Instant publishTime = Instant.parse("2026-06-23T12:34:56Z");
        long postId = 987654321L;

        String cursor = FeedCursor.encode(publishTime, postId);
        FeedCursor.FeedCursorPoint point = FeedCursor.decode(cursor).orElseThrow();

        assertThat(point.publishTime()).isEqualTo(publishTime);
        assertThat(point.postId()).isEqualTo(postId);
    }

    @Test
    void given_blankCursor_when_cacheSlot_then_head() {
        assertThat(FeedCursor.cacheSlot(null)).isEqualTo("head");
        assertThat(FeedCursor.cacheSlot("")).isEqualTo("head");
    }

    @Test
    void given_invalidCursor_when_decode_then_empty() {
        assertThat(FeedCursor.decode("not-valid-base64!!!")).isEmpty();
    }
}
