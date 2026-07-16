package com.chtholly.post.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 帖子详情响应。
 */
public record PostDetailResponse(
        String id,
        String slug,
        String title,
        String description,
        String contentUrl,
        List<String> images,
        List<String> tags,
        String authorId,
        String authorHandle,
        String authorAvatar,
        String authorNickname,
        String authorBio,
        String authorTagJson,
        Long likeCount,
        Long favoriteCount,
        Boolean liked,
        Boolean faved,
        Boolean isTop,
        String visible,
        String type,
        Instant publishTime
) {
    /**
     * 兼容不关心作者 handle 与简介的内部调用点。
     */
    public PostDetailResponse(
            String id,
            String slug,
            String title,
            String description,
            String contentUrl,
            List<String> images,
            List<String> tags,
            String authorId,
            String authorAvatar,
            String authorNickname,
            String authorTagJson,
            Long likeCount,
            Long favoriteCount,
            Boolean liked,
            Boolean faved,
            Boolean isTop,
            String visible,
            String type,
            Instant publishTime
    ) {
        this(id, slug, title, description, contentUrl, images, tags, authorId, null,
                authorAvatar, authorNickname, null, authorTagJson, likeCount, favoriteCount,
                liked, faved, isTop, visible, type, publishTime);
    }
}
