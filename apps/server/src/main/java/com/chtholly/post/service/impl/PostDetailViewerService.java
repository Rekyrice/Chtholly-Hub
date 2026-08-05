package com.chtholly.post.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.chtholly.post.model.PostDetailAudienceRow;
import com.chtholly.post.model.PostDetailRow;
import com.chtholly.relation.service.ActiveFollowingReader;
import com.chtholly.user.model.PublicAuthorSnapshot;
import com.chtholly.user.service.PublicAuthorQueryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Owns viewer-dependent post-detail authorization, counters, and author enrichment.
 *
 * <p>The query service remains responsible for origin selection and shared caching, while this
 * collaborator keeps audience facts authoritative and prevents viewer state from entering caches.
 */
@Service
public class PostDetailViewerService {

    private static final Logger log = LoggerFactory.getLogger(PostDetailViewerService.class);

    private final ObjectMapper objectMapper;
    private final CounterService counterService;
    private final PublicAuthorQueryService publicAuthorQueryService;
    private final ActiveFollowingReader activeFollowingReader;

    /**
     * Creates the viewer-specific detail projection service.
     *
     * @param objectMapper JSON codec for stored array columns
     * @param counterService live reaction reader
     * @param publicAuthorQueryService current public author projection
     * @param activeFollowingReader authoritative active-follow reader
     */
    public PostDetailViewerService(
            ObjectMapper objectMapper,
            CounterService counterService,
            PublicAuthorQueryService publicAuthorQueryService,
            ActiveFollowingReader activeFollowingReader) {
        this.objectMapper = objectMapper;
        this.counterService = counterService;
        this.publicAuthorQueryService = publicAuthorQueryService;
        this.activeFollowingReader = activeFollowingReader;
    }

    void assertReadable(PostDetailRow row, Long currentUserId) {
        assertReadable(row.getCreatorId(), row.getStatus(), row.getVisible(), currentUserId);
    }

    void assertReadable(PostDetailAudienceRow row, Long currentUserId) {
        assertReadable(row.getCreatorId(), row.getStatus(), row.getVisible(), currentUserId);
    }

    private void assertReadable(
            Long creatorId,
            String status,
            String visible,
            Long currentUserId) {
        boolean owner = currentUserId != null && currentUserId.equals(creatorId);
        if (owner) {
            return;
        }
        boolean published = "published".equals(status);
        if (published && "public".equals(visible)) {
            return;
        }
        boolean activeFollower = published
                && "followers".equals(visible)
                && currentUserId != null
                && creatorId != null
                && activeFollowingReader.isActiveFollowing(currentUserId, creatorId);
        if (!activeFollower) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无权限查看");
        }
    }

    PostDetailResponse mapRow(PostDetailRow row) {
        Map<String, Long> counts = counterService.getCounts(
                "post", String.valueOf(row.getId()), List.of("like", "fav"));
        return new PostDetailResponse(
                String.valueOf(row.getId()), row.getSlug(), row.getTitle(), row.getDescription(),
                row.getContentUrl(), parseArray(row.getImgUrls()), parseArray(row.getTags()),
                String.valueOf(row.getCreatorId()), row.getAuthorHandle(), row.getAuthorAvatar(),
                row.getAuthorNickname(), row.getAuthorBio(), row.getAuthorTagJson(),
                counts.getOrDefault("like", 0L), counts.getOrDefault("fav", 0L),
                null, null, row.getIsTop(), row.getVisible(), row.getType(), row.getPublishTime());
    }

    PostDetailResponse enrich(PostDetailResponse base, Long userId, boolean refreshCounts) {
        Long likes = base.likeCount();
        Long favorites = base.favoriteCount();
        if (refreshCounts) {
            Map<String, Long> counts = counterService.getCounts("post", base.id(), List.of("like", "fav"));
            likes = counts.getOrDefault("like", likes == null ? 0L : likes);
            favorites = counts.getOrDefault("fav", favorites == null ? 0L : favorites);
        }

        String authorHandle = base.authorHandle();
        String authorAvatar = base.authorAvatar();
        String authorNickname = base.authorNickname();
        String authorBio = base.authorBio();
        String authorTagJson = base.authorTagJson();
        try {
            long authorId = Long.parseLong(base.authorId());
            PublicAuthorSnapshot snapshot = publicAuthorQueryService.findById(authorId).orElse(null);
            if (snapshot != null) {
                authorHandle = snapshot.handle();
                authorAvatar = snapshot.avatar();
                authorNickname = snapshot.nickname();
                authorBio = snapshot.bio();
                authorTagJson = snapshot.tagsJson();
            }
        } catch (RuntimeException failure) {
            log.warn("Failed to refresh public author profile, authorId={}", base.authorId(), failure);
        }
        if (authorNickname == null || authorNickname.isBlank()) {
            authorNickname = "已注销用户";
        }
        return new PostDetailResponse(
                base.id(), base.slug(), base.title(), base.description(), base.contentUrl(), base.images(), base.tags(),
                base.authorId(), authorHandle, authorAvatar, authorNickname, authorBio, authorTagJson, likes, favorites,
                userId != null && counterService.isLiked("post", base.id(), userId),
                userId != null && counterService.isFaved("post", base.id(), userId),
                base.isTop(), base.visible(), base.type(), base.publishTime());
    }

    private List<String> parseArray(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception failure) {
            log.debug("Invalid post detail JSON array", failure);
            return Collections.emptyList();
        }
    }
}
