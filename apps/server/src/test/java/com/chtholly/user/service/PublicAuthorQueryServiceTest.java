package com.chtholly.user.service;

import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import com.chtholly.user.model.PublicAuthorSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicAuthorQueryServiceTest {

    @Mock
    private UserMapper userMapper;

    @Test
    void findByIdReturnsOnlyPublicAuthorFields() {
        Instant createdAt = Instant.parse("2026-02-14T10:00:00Z");
        User user = publicUser(7L, "rekyrice", "Rekyrice", createdAt);
        user.setPhone("13800000000");
        user.setEmail("private@example.com");
        when(userMapper.findPublicById(7L)).thenReturn(user);

        PublicAuthorQueryService service = new PublicAuthorQueryService(userMapper);

        PublicAuthorSnapshot snapshot = service.findById(7L).orElseThrow();

        assertThat(snapshot).isEqualTo(new PublicAuthorSnapshot(
                7L,
                "rekyrice",
                "Rekyrice",
                "/avatar.webp",
                "写点看完之后没有散掉的东西。",
                "[\"动画\",\"游戏\"]",
                createdAt
        ));
    }

    @Test
    void findByIdsUsesOneBatchQueryAndOmitsMissingUsers() {
        User first = publicUser(1L, "first", "First", Instant.parse("2026-03-01T00:00:00Z"));
        User second = publicUser(2L, "second", "Second", Instant.parse("2026-04-01T00:00:00Z"));
        when(userMapper.listPublicByIds(List.of(1L, 2L, 99L))).thenReturn(List.of(first, second));

        PublicAuthorQueryService service = new PublicAuthorQueryService(userMapper);

        Map<Long, PublicAuthorSnapshot> snapshots = service.findByIds(List.of(1L, 2L, 1L, 99L));

        assertThat(snapshots).containsOnlyKeys(1L, 2L);
        assertThat(snapshots.get(2L).handle()).isEqualTo("second");
        verify(userMapper).listPublicByIds(List.of(1L, 2L, 99L));
    }

    @Test
    void findByIdsSkipsMapperForEmptyInput() {
        PublicAuthorQueryService service = new PublicAuthorQueryService(userMapper);

        assertThat(service.findByIds(List.of())).isEmpty();

        verify(userMapper, never()).listPublicByIds(org.mockito.ArgumentMatchers.anyList());
    }

    private User publicUser(long id, String handle, String nickname, Instant createdAt) {
        return User.builder()
                .id(id)
                .handle(handle)
                .nickname(nickname)
                .avatar("/avatar.webp")
                .bio("写点看完之后没有散掉的东西。")
                .tagsJson("[\"动画\",\"游戏\"]")
                .createdAt(createdAt)
                .build();
    }
}
