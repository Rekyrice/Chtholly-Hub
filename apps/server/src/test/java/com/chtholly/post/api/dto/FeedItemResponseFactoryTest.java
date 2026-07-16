package com.chtholly.post.api.dto;

import com.chtholly.post.model.PostFeedRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeedItemResponseFactoryTest {

    @Test
    void fromRowMapsDatabaseJsonAndInteractionState() {
        PostFeedRow row = new PostFeedRow();
        row.setId(42L);
        row.setSlug("quiet-garden");
        row.setTitle("Quiet Garden");
        row.setDescription("A calm post");
        row.setTags("[\"anime\",\"daily\"]");
        row.setImgUrls("[\"/cover.jpg\",\"/inline.jpg\"]");
        row.setAuthorId(7L);
        row.setAuthorHandle("sakura-reader");
        row.setAuthorAvatar("/avatar.jpg");
        row.setAuthorNickname("Sakura");
        row.setAuthorTagJson("[\"reader\"]");
        row.setIsTop(true);
        row.setPublishTime(Instant.parse("2026-07-13T08:30:00Z"));

        FeedItemResponse item = FeedItemResponse.fromRow(
                row,
                new FeedItemResponse.CounterSnapshot(7L, 3L),
                true,
                false);

        assertThat(item.id()).isEqualTo("42");
        assertThat(item.tags()).containsExactly("anime", "daily");
        assertThat(item.coverImage()).isEqualTo("/cover.jpg");
        assertThat(item.authorId()).isEqualTo("7");
        assertThat(item.authorHandle()).isEqualTo("sakura-reader");
        assertThat(item.likeCount()).isEqualTo(7L);
        assertThat(item.favoriteCount()).isEqualTo(3L);
        assertThat(item.liked()).isTrue();
        assertThat(item.faved()).isFalse();
        assertThat(item.isTop()).isTrue();
        assertThat(item.publishTime()).isEqualTo(Instant.parse("2026-07-13T08:30:00Z"));
    }

    @Test
    void fromEsHitMapsDocumentFieldsAndSupportsCopyVariants() {
        Map<String, Object> source = Map.ofEntries(
                Map.entry("content_id", 99L),
                Map.entry("slug", "from-es"),
                Map.entry("title", "Indexed post"),
                Map.entry("description", "Indexed description"),
                Map.entry("tags", List.of("search", "anime")),
                Map.entry("img_urls", List.of("/es-cover.jpg")),
                Map.entry("author_id", 9L),
                Map.entry("author_handle", "yukino-notes"),
                Map.entry("author_avatar", "/es-avatar.jpg"),
                Map.entry("author_nickname", "Yukino"),
                Map.entry("author_tag_json", "[]"),
                Map.entry("publish_time", Instant.parse("2026-07-12T08:30:00Z").toEpochMilli()),
                Map.entry("like_count", 11L),
                Map.entry("favorite_count", 5L));

        FeedItemResponse item = FeedItemResponse.fromEsHit(source, true, true)
                .withDescription("Highlighted description")
                .withoutUserFlags();

        assertThat(item.id()).isEqualTo("99");
        assertThat(item.coverImage()).isEqualTo("/es-cover.jpg");
        assertThat(item.authorId()).isEqualTo("9");
        assertThat(item.authorHandle()).isEqualTo("yukino-notes");
        assertThat(item.description()).isEqualTo("Highlighted description");
        assertThat(item.likeCount()).isEqualTo(11L);
        assertThat(item.favoriteCount()).isEqualTo(5L);
        assertThat(item.liked()).isNull();
        assertThat(item.faved()).isNull();
        assertThat(item.publishTime()).isEqualTo(Instant.parse("2026-07-12T08:30:00Z"));
    }
}
