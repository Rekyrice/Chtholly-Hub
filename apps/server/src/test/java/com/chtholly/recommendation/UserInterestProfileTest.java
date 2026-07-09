package com.chtholly.recommendation;

import com.chtholly.counter.service.CounterService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.post.model.Post;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserInterestProfileTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private PostMapper postMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private CounterService counterService;
    @Mock
    private HashOperations<String, Object, Object> hashOps;
    @Mock
    private ZSetOperations<String, String> zSetOps;

    private UserInterestProfile profileService;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForHash()).thenReturn(hashOps);
        lenient().when(redis.opsForZSet()).thenReturn(zSetOps);
        profileService = new UserInterestProfile(
                redis,
                postMapper,
                userMapper,
                counterService,
                new TagJsonParser(new ObjectMapper().findAndRegisterModules()),
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void given_likedAnimePosts_when_updateProfile_then_tagWeightsNormalized() {
        Post post = new Post();
        post.setId(101L);
        post.setTags("[\"番剧\",\"治愈\"]");
        when(postMapper.findById(101L)).thenReturn(post);
        when(hashOps.entries(anyString())).thenReturn(Map.of());

        profileService.updateProfile(7L, 101L, "like");

        verify(hashOps).putAll(eq(UserInterestProfile.interestKey(7L)), any(Map.class));
        verify(zSetOps).add(eq(UserInterestProfile.interactionKey(7L)), eq("101"), anyDouble());
    }

    @Test
    void given_userTags_when_rebuildWithoutInteractions_then_usesUserTagsAsColdStart() {
        lenient().when(hashOps.entries(anyString())).thenReturn(Map.of());
        lenient().when(zSetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(null);
        lenient().when(postMapper.listRecentPublicSince(any(Instant.class), anyInt())).thenReturn(List.of());
        User user = User.builder().id(9L).tagsJson("[\"Java\",\"后端\"]").build();
        when(userMapper.findById(9L)).thenReturn(user);

        var profile = profileService.rebuildProfile(9L);

        assertThat(profile.tagWeights()).containsEntry("Java", 0.5);
        assertThat(profile.tagWeights()).containsEntry("后端", 0.5);
        assertThat(profile.updatedAt()).isBeforeOrEqualTo(Instant.now());
    }
}
