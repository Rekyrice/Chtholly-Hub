package com.chtholly.notification.listener;

import com.chtholly.counter.event.CounterEvent;
import com.chtholly.notification.event.CommentCreatedEvent;
import com.chtholly.notification.model.NotificationType;
import com.chtholly.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    void given_notificationCreateFails_when_onCommentCreated_then_doesNotPropagate() {
        CommentCreatedEvent event = new CommentCreatedEvent(
                100L, 1L, null, 3L, "nick", "avatar", 2L, "title", "slug", null);
        doThrow(new RuntimeException("db down")).when(notificationService)
                .create(eq(2L), eq(NotificationType.COMMENT_POST), any());

        assertThatCode(() -> listener.onCommentCreated(event)).doesNotThrowAnyException();
    }

    @Test
    void given_enrichedLikeEvent_when_onCounterEvent_then_usesPayloadWithoutDbLookup() {
        CounterEvent event = CounterEvent.of("101", "post", "42", "like", 1, 9L, 1);
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
    void given_reactionNotificationFails_when_onCounterEvent_then_propagatesForReplay() {
        CounterEvent event = CounterEvent.of("104", "post", "42", "like", 1, 9L, 1);
        event.setPostCreatorId(10L);
        when(notificationService.hasUnreadLikePost(10L, 42L)).thenReturn(false);
        doThrow(new RuntimeException("db down"))
                .when(notificationService)
                .create(eq(10L), eq(NotificationType.LIKE_POST), any());

        assertThatThrownBy(() -> listener.onCounterEvent(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");
    }

    @Test
    void reactionNotificationRunsInsideTheDurablePublicationClaim() throws Exception {
        Method method =
                NotificationEventListener.class.getMethod("onCounterEvent", CounterEvent.class);

        assertThat(method.getAnnotation(Async.class)).isNull();
    }

    @Test
    void given_missingPostCreatorId_when_onCounterEvent_then_skipsNotification() {
        CounterEvent event = CounterEvent.of("102", "post", "42", "like", 1, 9L, 1);

        listener.onCounterEvent(event);

        verifyNoInteractions(notificationService);
    }

    @Test
    void given_selfLike_when_onCounterEvent_then_skipsNotification() {
        CounterEvent event = CounterEvent.of("103", "post", "42", "like", 1, 10L, 1);
        event.setPostCreatorId(10L);

        listener.onCounterEvent(event);

        verifyNoInteractions(notificationService);
    }
}
