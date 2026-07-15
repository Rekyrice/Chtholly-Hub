package com.chtholly.user.service.impl;

import com.chtholly.post.mapper.PostMapper;
import com.chtholly.user.api.dto.PublicUserResponse;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserPublicServiceImplTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private PostMapper postMapper;

    @Test
    void getByHandleReturnsPublicProfileWithCreatedAt() {
        Instant createdAt = Instant.parse("2026-07-01T08:30:00Z");
        User user = User.builder()
                .id(7L)
                .handle("rekyrice")
                .nickname("Reky")
                .avatar("https://example.com/avatar.png")
                .bio("Quiet writer")
                .tagsJson("[\"动画\",\"游戏\"]")
                .createdAt(createdAt)
                .build();
        when(userMapper.findByHandle("rekyrice")).thenReturn(user);
        when(postMapper.countPublicPublishedByCreator(7L)).thenReturn(12L);

        UserPublicServiceImpl service = new UserPublicServiceImpl(userMapper, postMapper, new ObjectMapper());

        PublicUserResponse response = service.getByHandle("rekyrice");

        assertThat(response.id()).isEqualTo("7");
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.tags()).containsExactly("动画", "游戏");
    }

    @Test
    void getByHandleReturnsEmptyTagsWhenStoredJsonIsMalformed() {
        User user = User.builder()
                .id(8L)
                .handle("quiet-user")
                .nickname("Quiet")
                .tagsJson("not-json")
                .build();
        when(userMapper.findByHandle("quiet-user")).thenReturn(user);
        when(postMapper.countPublicPublishedByCreator(8L)).thenReturn(0L);

        UserPublicServiceImpl service = new UserPublicServiceImpl(userMapper, postMapper, new ObjectMapper());

        PublicUserResponse response = service.getByHandle("quiet-user");

        assertThat(response.tags()).isEmpty();
    }
}
