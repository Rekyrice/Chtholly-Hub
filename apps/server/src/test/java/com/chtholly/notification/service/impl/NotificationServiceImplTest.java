package com.chtholly.notification.service.impl;

import com.chtholly.notification.mapper.NotificationMapper;
import com.chtholly.notification.model.NotificationCountStats;
import com.chtholly.notification.model.NotificationRow;
import com.chtholly.notification.model.NotificationType;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private SnowflakeIdGenerator idGen;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(notificationMapper, idGen, new ObjectMapper());
    }

    @Test
    void listUsesMergedCountQuery() {
        NotificationCountStats stats = new NotificationCountStats();
        stats.setTotal(10);
        stats.setUnread(3);
        when(notificationMapper.listByUser(eq(1L), eq(20), eq(0))).thenReturn(List.of());
        when(notificationMapper.countStatsByUser(1L)).thenReturn(stats);

        var response = service.list(1L, 1, 20);

        assertThat(response.total()).isEqualTo(10);
        assertThat(response.unreadCount()).isEqualTo(3);
        verify(notificationMapper).countStatsByUser(1L);
    }

    @Test
    void unknownTypeDoesNotThrow() {
        NotificationRow row = new NotificationRow();
        row.setId(1L);
        row.setUserId(2L);
        row.setType("FUTURE_TYPE");
        row.setPayload("{\"actorNickname\":\"Alice\"}");
        row.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));

        when(notificationMapper.listByUser(eq(2L), anyInt(), anyInt())).thenReturn(List.of(row));
        NotificationCountStats stats = new NotificationCountStats();
        stats.setTotal(1);
        stats.setUnread(1);
        when(notificationMapper.countStatsByUser(2L)).thenReturn(stats);

        var response = service.list(2L, 1, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).type()).isEqualTo("FUTURE_TYPE");
        assertThat(response.items().get(0).message()).isEqualTo("你有一条新通知");
    }

    @Test
    void hasUnreadLikePostDelegatesToMapper() {
        when(notificationMapper.countUnreadLikePost(5L, NotificationType.LIKE_POST.name(), 99L)).thenReturn(1L);
        assertThat(service.hasUnreadLikePost(5L, 99L)).isTrue();
    }

    @Test
    void cleanExpiredAllDelegatesToMapper() {
        when(notificationMapper.deleteExpiredRead(90)).thenReturn(7);
        assertThat(service.cleanExpiredAll(90)).isEqualTo(7);
    }
}
