package com.chtholly.notification.listener;

import com.chtholly.counter.event.CounterEvent;
import com.chtholly.notification.model.NotificationType;
import com.chtholly.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    @Mock
    private NotificationService notificationService;

    private NotificationEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new NotificationEventListener(notificationService);
    }

    @Test
    void given_enrichedLikeEvent_when_onCounterEvent_then_usesPayloadWithoutDbLookup() {
        CounterEvent event = CounterEvent.of("post", "42", "like", 0, 9L, 1);
        event.setPostCreatorId(10L);
        event.setPostTitle("Re:Zero");
        event.setPostSlug("re-zero");
        event.setActorNickname("Alice");
        event.setActorAvatar("avatar.png");
        when(notificationService.hasUnreadLikePost(10L, 42L)).thenReturn(false);

        listener.onCounterEvent(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).create(eq(10L), eq(NotificationType.LIKE_POST), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("postTitle")).isEqualTo("Re:Zero");
        assertThat(payload.get("postSlug")).isEqualTo("re-zero");
        assertThat(payload.get("actorNickname")).isEqualTo("Alice");
    }

    @Test
    void given_missingPostCreatorId_when_onCounterEvent_then_skipsNotification() {
        CounterEvent event = CounterEvent.of("post", "42", "like", 0, 9L, 1);

        listener.onCounterEvent(event);

        verifyNoInteractions(notificationService);
    }

    @Test
    void given_selfLike_when_onCounterEvent_then_skipsNotification() {
        CounterEvent event = CounterEvent.of("post", "42", "like", 0, 10L, 1);
        event.setPostCreatorId(10L);

        listener.onCounterEvent(event);

        verifyNoInteractions(notificationService);
    }
}
