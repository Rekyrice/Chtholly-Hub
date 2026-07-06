package com.chtholly.user.service.impl;

import com.chtholly.post.mapper.PostMapper;
import com.chtholly.user.api.dto.PublicUserResponse;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
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
                .createdAt(createdAt)
                .build();
        when(userMapper.findByHandle("rekyrice")).thenReturn(user);
        when(postMapper.countPublicPublishedByCreator(7L)).thenReturn(12L);

        UserPublicServiceImpl service = new UserPublicServiceImpl(userMapper, postMapper);

        PublicUserResponse response = service.getByHandle("rekyrice");

        assertThat(response.id()).isEqualTo("7");
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }
}
