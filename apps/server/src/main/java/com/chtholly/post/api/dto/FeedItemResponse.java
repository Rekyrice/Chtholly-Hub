package com.chtholly.post.api.dto;

import com.chtholly.post.model.PostFeedRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Feed item returned by database-backed and Elasticsearch-backed listings.
 */
@Slf4j
public record FeedItemResponse(
        String id,
        String slug,
        String title,
        String description,
        String coverImage,
        List<String> tags,
        String authorAvatar,
        String authorNickname,
        String tagJson,
        Long likeCount,
        Long favoriteCount,
        Boolean liked,
        Boolean faved,
        Boolean isTop,
        Instant publishTime
) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public FeedItemResponse(
            String id,
            String slug,
            String title,
            String description,
            String coverImage,
            List<String> tags,
            String authorAvatar,
            String authorNickname,
            String tagJson,
            Long likeCount,
            Long favoriteCount,
            Boolean liked,
            Boolean faved,
            Boolean isTop) {
        this(id, slug, title, description, coverImage, tags, authorAvatar, authorNickname,
                tagJson, likeCount, favoriteCount, liked, faved, isTop, null);
    }

    /**
     * Creates a feed item from a database projection.
     *
     * @param row database feed row
     * @param counts current aggregate counters
     * @param liked whether the current user liked the post
     * @param faved whether the current user favorited the post
     * @return mapped feed item
     */
    public static FeedItemResponse fromRow(
            PostFeedRow row,
            CounterSnapshot counts,
            boolean liked,
            boolean faved) {
        List<String> images = parseJsonList(row.getImgUrls());
        return create(
                String.valueOf(row.getId()), row.getSlug(), row.getTitle(), row.getDescription(),
                images.isEmpty() ? null : images.getFirst(), parseJsonList(row.getTags()),
                row.getAuthorAvatar(), row.getAuthorNickname(), row.getAuthorTagJson(),
                counts.likes(), counts.favorites(), liked, faved, row.getIsTop(), row.getPublishTime());
    }

    /**
     * Creates a feed item from an Elasticsearch document.
     *
     * @param source Elasticsearch document source
     * @param liked whether the current user liked the post
     * @param faved whether the current user favorited the post
     * @return mapped feed item
     */
    public static FeedItemResponse fromEsHit(Map<String, Object> source, boolean liked, boolean faved) {
        List<String> images = asStringList(source.get("img_urls"));
        return create(
                asString(source.get("content_id")), asString(source.get("slug")),
                asString(source.get("title")), asString(source.get("description")),
                images.isEmpty() ? null : images.getFirst(), asStringList(source.get("tags")),
                asString(source.get("author_avatar")), asString(source.get("author_nickname")),
                asString(source.get("author_tag_json")), asLong(source.get("like_count")),
                asLong(source.get("favorite_count")), liked, faved, asBoolean(source.get("is_top")),
                asInstant(source.get("publish_time")));
    }

    public FeedItemResponse withDescription(String nextDescription) {
        return copy(nextDescription, likeCount, favoriteCount, liked, faved, isTop);
    }

    public FeedItemResponse withCounts(long likes, long favorites) {
        return copy(description, likes, favorites, liked, faved, isTop);
    }

    public FeedItemResponse withUserFlags(Boolean nextLiked, Boolean nextFaved) {
        return copy(description, likeCount, favoriteCount, nextLiked, nextFaved, isTop);
    }

    public FeedItemResponse withoutUserFlags() {
        return withUserFlags(null, null);
    }

    public FeedItemResponse withTop(Boolean top) {
        return copy(description, likeCount, favoriteCount, liked, faved, top);
    }

    private FeedItemResponse copy(
            String nextDescription,
            Long nextLikeCount,
            Long nextFavoriteCount,
            Boolean nextLiked,
            Boolean nextFaved,
            Boolean nextIsTop) {
        return create(id, slug, title, nextDescription, coverImage, tags, authorAvatar, authorNickname,
                tagJson, nextLikeCount, nextFavoriteCount, nextLiked, nextFaved, nextIsTop, publishTime);
    }

    private static FeedItemResponse create(
            String id,
            String slug,
            String title,
            String description,
            String coverImage,
            List<String> tags,
            String authorAvatar,
            String authorNickname,
            String tagJson,
            Long likeCount,
            Long favoriteCount,
            Boolean liked,
            Boolean faved,
            Boolean isTop,
            Instant publishTime) {
        return new FeedItemResponse(id, slug, title, description, coverImage, tags, authorAvatar,
                authorNickname, tagJson, likeCount, favoriteCount, liked, faved, isTop, publishTime);
    }

    private static List<String> parseJsonList(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JSON.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            log.warn("Feed item JSON array could not be parsed: {}", exception.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof String text) {
            return parseJsonList(text);
        }
        return Collections.emptyList();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            log.warn("Feed item numeric field could not be parsed: {}", value);
            return null;
        }
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    private static Instant asInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (DateTimeParseException exception) {
            log.warn("Feed item date field could not be parsed: {}", value);
            return null;
        }
    }

    /** Aggregate counters required while mapping a database row. */
    public record CounterSnapshot(long likes, long favorites) {
        public static CounterSnapshot from(Map<String, Long> counts) {
            return new CounterSnapshot(
                    counts.getOrDefault("like", 0L),
                    counts.getOrDefault("fav", 0L));
        }
    }
}
