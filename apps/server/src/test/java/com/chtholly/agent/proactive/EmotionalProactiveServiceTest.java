package com.chtholly.agent.proactive;

import com.chtholly.agent.cognitive.ExperienceService;
import com.chtholly.agent.cognitive.Observation;
import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.agent.state.CharacterStateService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmotionalProactiveServiceTest {
    @Test
    void checkTriggersBroadcastsHighestValueThought() {
        CharacterStateService state = mock(CharacterStateService.class);
        NotificationService notifications = mock(NotificationService.class);
        ProactiveNotificationDispatcher dispatcher = mock(ProactiveNotificationDispatcher.class);
        ExperienceService experiences = mock(ExperienceService.class);
        ProactiveTriggerEngine.UserActivityProvider activity = mock(ProactiveTriggerEngine.UserActivityProvider.class);
        when(activity.findAbsentUsers(any())).thenReturn(List.of());
        when(experiences.getRecentExperiences(3)).thenReturn(List.of(
                new Observation("quiet", 0.5, Instant.now(), "test"),
                new Observation("important", 0.9, Instant.now(), "test")));

        new EmotionalProactiveService(state, notifications, dispatcher, experiences, activity, prompt -> "")
                .checkTriggers();

        verify(notifications).broadcast(any(Notification.class));
    }
}
