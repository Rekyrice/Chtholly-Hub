package com.chtholly.post.service.impl;

import com.chtholly.cache.hotkey.HotKeyDetector;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.api.dto.PostDetailResponse;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.PostDetailEtagRow;
import com.chtholly.user.model.PublicAuthorSnapshot;
import com.chtholly.user.service.PublicAuthorQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostDetailQueryServiceTest {

    @Mock private PostMapper mapper;
    @Mock private CounterService counterService;
    @Mock private StringRedisTemplate redis;
    @Mock private HotKeyDetector hotKey;
    @Mock private PublicAuthorQueryService publicAuthorQueryService;

    @Test
    void cacheHitOverlaysLatestAuthorProfileFromMysql() {
        Cache<String, PostDetailResponse> cache = Caffeine.newBuilder().build();
        PostDetailResponse stale = detail(
                "old-handle",
                "/old-avatar.webp",
                "旧昵称",
                "旧简介",
                "[\"旧标签\"]"
        );
        cache.put(PostDetailQueryService.cacheKey(42L), stale);
        when(counterService.getCounts("post", "42", List.of("like", "fav")))
                .thenReturn(Map.of("like", 3L, "fav", 2L));
        when(redis.getExpire(PostDetailQueryService.cacheKey(42L))).thenReturn(120L);
        when(redis.getExpire("feed:item:42")).thenReturn(120L);
        when(hotKey.ttlForPublic(60, "post:42")).thenReturn(60);
        when(publicAuthorQueryService.findById(7L)).thenReturn(Optional.of(new PublicAuthorSnapshot(
                7L,
                "rekyrice",
                "Rekyrice",
                "/new-avatar.webp",
                "写点看完之后没有散掉的东西。",
                "[\"动画\",\"游戏\"]",
                Instant.parse("2026-02-14T10:00:00Z")
        )));
        PostDetailQueryService service = new PostDetailQueryService(
                mapper,
                new ObjectMapper(),
                counterService,
                redis,
                cache,
                hotKey,
                publicAuthorQueryService
        );

        PostDetailResponse response = service.getDetail(42L, null);

        assertThat(response.authorHandle()).isEqualTo("rekyrice");
        assertThat(response.authorAvatar()).isEqualTo("/new-avatar.webp");
        assertThat(response.authorNickname()).isEqualTo("Rekyrice");
        assertThat(response.authorBio()).isEqualTo("写点看完之后没有散掉的东西。");
        assertThat(response.authorTagJson()).isEqualTo("[\"动画\",\"游戏\"]");
    }

    @Test
    void cacheHitKeepsStoredSnapshotWhenAuthorNoLongerExists() {
        Cache<String, PostDetailResponse> cache = Caffeine.newBuilder().build();
        PostDetailResponse stale = detail("old-handle", "/old.webp", "旧昵称", "旧简介", "[]");
        cache.put(PostDetailQueryService.cacheKey(42L), stale);
        when(counterService.getCounts("post", "42", List.of("like", "fav"))).thenReturn(Map.of());
        when(redis.getExpire(PostDetailQueryService.cacheKey(42L))).thenReturn(120L);
        when(redis.getExpire("feed:item:42")).thenReturn(120L);
        when(hotKey.ttlForPublic(60, "post:42")).thenReturn(60);
        when(publicAuthorQueryService.findById(7L)).thenReturn(Optional.empty());
        PostDetailQueryService service = new PostDetailQueryService(
                mapper,
                new ObjectMapper(),
                counterService,
                redis,
                cache,
                hotKey,
                publicAuthorQueryService
        );

        PostDetailResponse response = service.getDetail(42L, null);

        assertThat(response.authorHandle()).isEqualTo("old-handle");
        assertThat(response.authorBio()).isEqualTo("旧简介");
    }

    @Test
    void authorProfileUpdateChangesDetailEtagEvenWhenPostIsUnchanged() {
        PostDetailEtagRow before = new PostDetailEtagRow();
        before.setStatus("published");
        before.setUpdateTime(Instant.parse("2026-07-01T00:00:00Z"));
        before.setAuthorUpdateTime(Instant.parse("2026-07-02T00:00:00Z"));
        PostDetailEtagRow after = new PostDetailEtagRow();
        after.setStatus("published");
        after.setUpdateTime(before.getUpdateTime());
        after.setAuthorUpdateTime(Instant.parse("2026-07-03T00:00:00Z"));
        when(mapper.findDetailEtagById(42L)).thenReturn(before, after);
        PostDetailQueryService service = new PostDetailQueryService(
                mapper, new ObjectMapper(), counterService, redis, Caffeine.newBuilder().build(), hotKey,
                publicAuthorQueryService);

        String oldEtag = service.computeEtag(42L);
        String newEtag = service.computeEtag(42L);

        assertThat(newEtag).isNotEqualTo(oldEtag);
    }

    private PostDetailResponse detail(
            String handle,
            String avatar,
            String nickname,
            String bio,
            String tagJson
    ) {
        return new PostDetailResponse(
                "42", "post-slug", "标题", "摘要", "/content.md", List.of(), List.of("动画"),
                "7", handle, avatar, nickname, bio, tagJson,
                0L, 0L, false, false, false, "public", "article",
                Instant.parse("2026-07-01T00:00:00Z")
        );
    }
}
