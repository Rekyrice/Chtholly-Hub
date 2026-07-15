package com.chtholly.post.api;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.post.api.dto.FeedItemResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostControllerCacheFingerprintTest {

    @Test
    void authorProfileChangeInvalidatesPublicFeedEtagWithinSameHour() {
        FeedItemResponse oldAuthor = item("旧昵称", "/old.webp", "old_handle");
        FeedItemResponse newAuthor = item("新昵称", "/new.webp", "new_handle");

        String before = PostController.computeFeedEtag(
                PageResponse.offset(List.of(oldAuthor), 1, 20, 1L), "feed-key", 123L);
        String after = PostController.computeFeedEtag(
                PageResponse.offset(List.of(newAuthor), 1, 20, 1L), "feed-key", 123L);

        assertThat(after).isNotEqualTo(before);
    }

    private FeedItemResponse item(String nickname, String avatar, String handle) {
        return new FeedItemResponse(
                "99", "slug", "标题", "摘要", null, List.of("动画"), "42", handle, avatar, nickname,
                "[\"动画\"]", 3L, 1L, false, false, null, Instant.parse("2026-07-01T00:00:00Z"));
    }
}
