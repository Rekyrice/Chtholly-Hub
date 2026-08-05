package com.chtholly.post.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.content.ContentIntelligenceReader;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.chtholly.post.service.PostFeedService;
import com.chtholly.post.service.PostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostControllerCachePolicyTest {

    @Mock private PostService postService;
    @Mock private PostFeedService feedService;
    @Mock private JwtService jwtService;
    @Mock private ContentIntelligenceReader contentIntelligenceReader;
    @Mock private Jwt jwt;

    private PostController controller;

    @BeforeEach
    void setUp() {
        controller = new PostController(postService, feedService, jwtService, contentIntelligenceReader);
    }

    @Test
    void anonymousDetailAuthorizesBeforeHonoringConditionalRequest() {
        PostDetailResponse detail = detail("public", false, false);
        when(postService.getDetail(42L, null)).thenReturn(detail);
        when(postService.computeDetailEtag(42L)).thenReturn("detail-etag");
        String responseEtag = PostController.computeDetailResponseEtag("detail-etag", detail);

        ResponseEntity<PostDetailResponse> response = controller.detail(42L, responseEtag, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getHeaders().getCacheControl()).contains("public");
        assertThat(response.getHeaders().getVary()).containsExactly(HttpHeaders.AUTHORIZATION);
        InOrder authorizationBeforeValidator = inOrder(postService);
        authorizationBeforeValidator.verify(postService).getDetail(42L, null);
        authorizationBeforeValidator.verify(postService).computeDetailEtag(42L);
    }

    @Test
    void authenticatedDetailNeverUsesSharedConditionalCache() {
        PostDetailResponse detail = detail("public", true, false);
        when(jwtService.extractUserId(jwt)).thenReturn(7L);
        when(postService.getDetail(42L, 7L)).thenReturn(detail);

        ResponseEntity<PostDetailResponse> response = controller.detail(42L, "\"detail-etag\"", jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(detail);
        assertThat(response.getHeaders().getCacheControl()).contains("private");
        assertThat(response.getHeaders().containsKey(HttpHeaders.ETAG)).isFalse();
        assertThat(response.getHeaders().getVary()).containsExactly(HttpHeaders.AUTHORIZATION);
        verify(postService, never()).computeDetailEtag(42L);
    }

    @Test
    void authenticatedSlugDetailNeverUsesSharedConditionalCache() {
        PostDetailResponse detail = detail("private", false, false);
        when(jwtService.extractUserId(jwt)).thenReturn(7L);
        when(postService.getDetailBySlug("quiet-room", 7L)).thenReturn(detail);

        ResponseEntity<PostDetailResponse> response =
                controller.detailBySlug("quiet-room", "\"detail-etag\"", jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("private");
        assertThat(response.getHeaders().containsKey(HttpHeaders.ETAG)).isFalse();
        assertThat(response.getHeaders().getVary()).containsExactly(HttpHeaders.AUTHORIZATION);
        verify(postService, never()).computeDetailEtagBySlug("quiet-room");
    }

    @Test
    void authenticatedPublicFeedIsPrivateAndSkipsSharedEtag() {
        PageResponse<FeedItemResponse> page = PageResponse.offset(List.of(), 1, 20, 0L);
        when(jwtService.extractUserId(jwt)).thenReturn(7L);
        when(feedService.getPublicFeed(1, null, 20, null, null, 7L)).thenReturn(page);

        ResponseEntity<PageResponse<FeedItemResponse>> response =
                controller.feed(1, null, 20, null, null, "\"feed-etag\"", jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("private");
        assertThat(response.getHeaders().containsKey(HttpHeaders.ETAG)).isFalse();
        assertThat(response.getHeaders().getVary()).containsExactly(HttpHeaders.AUTHORIZATION);
        verify(feedService, never()).publicFeedPageKey(1, null, 20, null, null);
    }

    @Test
    void anonymousPublicFeedRetainsSharedConditionalCaching() {
        PageResponse<FeedItemResponse> page = PageResponse.offset(List.of(), 1, 20, 0L);
        when(feedService.getPublicFeed(1, null, 20, null, null, null)).thenReturn(page);
        when(feedService.publicFeedPageKey(1, null, 20, null, null)).thenReturn("feed-key");

        ResponseEntity<PageResponse<FeedItemResponse>> response =
                controller.feed(1, null, 20, null, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("public");
        assertThat(response.getHeaders().containsKey(HttpHeaders.ETAG)).isTrue();
        assertThat(response.getHeaders().getVary()).containsExactly(HttpHeaders.AUTHORIZATION);
    }

    @Test
    void anonymousDetailEtagChangesWhenReactionCountsChange() {
        PostDetailResponse before = detail("public", false, false);
        PostDetailResponse after = new PostDetailResponse(
                before.id(), before.slug(), before.title(), before.description(), before.contentUrl(),
                before.images(), before.tags(), before.authorId(), before.authorHandle(), before.authorAvatar(),
                before.authorNickname(), before.authorBio(), before.authorTagJson(), 4L, before.favoriteCount(),
                before.liked(), before.faved(), before.isTop(), before.visible(), before.type(), before.publishTime());

        assertThat(PostController.computeDetailResponseEtag("persisted-version", before))
                .isNotEqualTo(PostController.computeDetailResponseEtag("persisted-version", after));
    }

    @Test
    void anonymousDetailEtagAlsoTracksTheRenderedBody() {
        PostDetailResponse before = detail("public", false, false);
        PostDetailResponse after = new PostDetailResponse(
                before.id(), before.slug(), "更新后的标题", before.description(), before.contentUrl(),
                before.images(), before.tags(), before.authorId(), before.authorHandle(), before.authorAvatar(),
                before.authorNickname(), before.authorBio(), before.authorTagJson(), before.likeCount(),
                before.favoriteCount(), before.liked(), before.faved(), before.isTop(), before.visible(),
                before.type(), before.publishTime());

        assertThat(PostController.computeDetailResponseEtag("persisted-version", before))
                .isNotEqualTo(PostController.computeDetailResponseEtag("persisted-version", after));
    }

    private PostDetailResponse detail(String visibility, boolean liked, boolean faved) {
        return new PostDetailResponse(
                "42",
                "quiet-room",
                "标题",
                "摘要",
                "/content/42.md",
                List.of(),
                List.of("动画"),
                "7",
                "author",
                "/avatar.webp",
                "作者",
                "简介",
                "[]",
                3L,
                1L,
                liked,
                faved,
                false,
                visibility,
                "image_text",
                Instant.parse("2026-07-01T00:00:00Z"));
    }
}
