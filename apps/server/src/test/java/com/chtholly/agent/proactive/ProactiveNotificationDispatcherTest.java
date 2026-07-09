package com.chtholly.agent.proactive;

import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationChannel;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.agent.state.BehaviorProb;
import com.chtholly.agent.state.CharacterState;
import com.chtholly.agent.state.CharacterStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProactiveNotificationDispatcherTest {

    @Mock
    private NotificationService notificationService;
    @Mock
    private ProactiveRateLimiter rateLimiter;
    @Mock
    private CharacterStateService characterStateService;

    private ProactiveNotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ProactiveNotificationDispatcher(notificationService, rateLimiter, characterStateService);
    }

    @Test
    void given_behaviorProbOne_when_send_then_deliversNotification() {
        when(rateLimiter.canSend(3L)).thenReturn(true);
        CharacterState state = new CharacterState(
                CharacterState.defaultState(Instant.now()).personality(),
                CharacterState.defaultState(Instant.now()).mood(),
                CharacterState.defaultState(Instant.now()).relationship(),
                CharacterState.defaultState(Instant.now()).needs(),
                new BehaviorProb(1.0, 1.0, 1.0));
        when(characterStateService.load(3L)).thenReturn(state);
        Notification notification = new Notification("hot-digest", "摘要", NotificationChannel.FLOATING);

        boolean sent = dispatcher.send(3L, notification, ProactiveNotificationDispatcher.Category.GREET);

        assertThat(sent).isTrue();
        verify(notificationService).send(3L, notification);
        verify(rateLimiter).recordSend(3L);
    }

    @Test
    void given_dailyLimitReached_when_send_then_skips() {
        when(rateLimiter.canSend(3L)).thenReturn(false);

        boolean sent = dispatcher.send(
                3L,
                new Notification("hot-digest", "摘要", NotificationChannel.FLOATING),
                ProactiveNotificationDispatcher.Category.GREET);

        assertThat(sent).isFalse();
        verify(notificationService, never()).send(anyLong(), any());
    }

    @Test
    void given_zeroRecommendProb_when_sendRecommend_then_skips() {
        when(rateLimiter.canSend(3L)).thenReturn(true);
        CharacterState state = new CharacterState(
                CharacterState.defaultState(Instant.now()).personality(),
                CharacterState.defaultState(Instant.now()).mood(),
                CharacterState.defaultState(Instant.now()).relationship(),
                CharacterState.defaultState(Instant.now()).needs(),
                new BehaviorProb(1.0, 1.0, 0.0));
        when(characterStateService.load(3L)).thenReturn(state);

        boolean sent = dispatcher.send(
                3L,
                new Notification("weekly-curation", "精选", NotificationChannel.FLOATING),
                ProactiveNotificationDispatcher.Category.RECOMMEND);

        assertThat(sent).isFalse();
        verify(notificationService, never()).send(anyLong(), any());
    }
}
