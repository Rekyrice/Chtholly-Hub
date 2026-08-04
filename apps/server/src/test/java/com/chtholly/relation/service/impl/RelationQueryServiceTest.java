package com.chtholly.relation.service.impl;

import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.mapper.RelationMapper.RelationPageRow;
import com.chtholly.relation.util.RelationCursor;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** Characterizes relation status and profile-page assembly after query extraction. */
@ExtendWith(MockitoExtension.class)
class RelationQueryServiceTest {

    @Mock private RelationMapper relationMapper;
    @Mock private RelationProjectionCache projectionCache;
    @Mock private UserMapper userMapper;

    private RelationQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new RelationQueryService(
                relationMapper, projectionCache, userMapper);
    }

    @Test
    void relationStatusKeepsStableKeyOrderAndMutualMeaning() {
        when(relationMapper.existsFollowing(11L, 22L)).thenReturn(1);
        when(relationMapper.existsFollowing(22L, 11L)).thenReturn(1);

        assertThat(queryService.relationStatus(11L, 22L))
                .containsExactly(
                        org.assertj.core.data.MapEntry.entry("following", true),
                        org.assertj.core.data.MapEntry.entry("followedBy", true),
                        org.assertj.core.data.MapEntry.entry("mutual", true));
    }

    @Test
    void cursorProfilePagePreservesRelationOrderAndBuildsNextCursor() {
        Date newest = new Date(2_345L);
        Date boundary = new Date(1_234L);
        when(relationMapper.listFollowingPage(11L, null, null, 3))
                .thenReturn(List.of(
                        new RelationPageRow(33L, newest),
                        new RelationPageRow(22L, boundary),
                        new RelationPageRow(44L, boundary)));
        when(userMapper.listByIds(List.of(33L, 22L)))
                .thenReturn(List.of(user(22L, "二二"), user(33L, "三三")));

        var page = queryService.followingProfilesPage(
                11L, 2, null, null, null);

        assertThat(page.items()).extracting(item -> item.id())
                .containsExactly(33L, 22L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor())
                .isEqualTo(RelationCursor.encode(1_234L, 22L));
    }

    @Test
    void fullCursorPassesBothKeysetComponentsToTheMapper() {
        String cursor = RelationCursor.encode(1_234L, 22L);
        when(relationMapper.listFollowingPage(
                11L, new Date(1_234L), 22L, 3))
                .thenReturn(List.of(
                        new RelationPageRow(21L, new Date(1_234L)),
                        new RelationPageRow(44L, new Date(1_000L))));
        when(userMapper.listByIds(List.of(21L, 44L)))
                .thenReturn(List.of(user(44L, "四四"), user(21L, "二一")));

        var page = queryService.followingProfilesPage(
                11L, 2, cursor, null, null);

        assertThat(page.items()).extracting(item -> item.id())
                .containsExactly(21L, 44L);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void numericCursorKeepsLegacyProjectionCompatibility() {
        when(projectionCache.followingCursor(11L, 3, 1_234L))
                .thenReturn(List.of(33L));
        when(userMapper.listByIds(List.of(33L)))
                .thenReturn(List.of(user(33L, "三三")));

        var page = queryService.followingProfilesPage(
                11L, 2, "1234", null, null);

        assertThat(page.items()).extracting(item -> item.id())
                .containsExactly(33L);
    }

    @Test
    void malformedCursorIsRejectedInsteadOfSilentlyRestartingFromHead() {
        assertThatThrownBy(() -> queryService.followingProfilesPage(
                11L, 2, "not-a-relation-cursor", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    private static User user(long id, String nickname) {
        return User.builder()
                .id(id)
                .nickname(nickname)
                .tagsJson("[]")
                .build();
    }
}
