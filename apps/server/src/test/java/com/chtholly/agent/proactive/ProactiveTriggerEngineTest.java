package com.chtholly.agent.proactive;

import com.chtholly.agent.cognitive.ExperienceService;
import com.chtholly.agent.cognitive.Observation;
import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationChannel;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.post.service.PostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProactiveTriggerEngineTest {

    private CharacterStateService characterStateService;
    private PostService postService;
    private NotificationService notificationService;
    private ExperienceService experienceService;

    @BeforeEach
    void setUp() {
        characterStateService = mock(CharacterStateService.class);
        postService = mock(PostService.class);
        notificationService = mock(NotificationService.class);
        experienceService = mock(ExperienceService.class);
    }

    @Test
    void checkTriggersSendsMissingYouForAbsentUsers() {
        ProactiveTriggerEngine.UserActivityProvider activityProvider = new StubActivityProvider(
                List.of(new ProactiveTriggerEngine.AbsentUser(
                        7L,
                        "Reky",
                        Instant.parse("2026-07-01T00:00:00Z"))),
                List.of());
        ProactiveTriggerEngine engine = new ProactiveTriggerEngine(
                characterStateService,
                postService,
                notificationService,
                experienceService,
                activityProvider,
                prompt -> "I kept your seat by the window.");
        when(experienceService.getRecentExperiences(3)).thenReturn(List.of());

        engine.checkTriggers();

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).send(org.mockito.ArgumentMatchers.eq(7L), notificationCaptor.capture());
        Notification notification = notificationCaptor.getValue();
        assertThat(notification.type()).isEqualTo("missing-you");
        assertThat(notification.message()).isEqualTo("I kept your seat by the window.");
        assertThat(notification.channel()).isEqualTo(NotificationChannel.FLOATING);
    }

    @Test
    void checkTriggersBroadcastsMostValuableRecentThought() {
        ProactiveTriggerEngine engine = new ProactiveTriggerEngine(
                characterStateService,
                postService,
                notificationService,
                experienceService,
                new StubActivityProvider(List.of(), List.of()),
                prompt -> "");
        Observation ordinary = new Observation(
                "Ordinary thought.",
                0.6,
                Instant.parse("2026-07-04T01:00:00Z"),
                "cognitive-cycle");
        Observation valuable = new Observation(
                "This one feels worth saying.",
                0.91,
                Instant.parse("2026-07-04T02:00:00Z"),
                "cognitive-cycle");
        when(experienceService.getRecentExperiences(3)).thenReturn(List.of(ordinary, valuable));

        engine.checkTriggers();

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).broadcast(notificationCaptor.capture());
        Notification notification = notificationCaptor.getValue();
        assertThat(notification.type()).isEqualTo("thought");
        assertThat(notification.message()).isEqualTo("This one feels worth saying.");
        assertThat(notification.channel()).isEqualTo(NotificationChannel.AGENT_PAGE);
    }

    @Test
    void checkTriggersSendsUnreadPostRecommendations() {
        ProactiveTriggerEngine.UserActivityProvider activityProvider = new StubActivityProvider(
                List.of(),
                List.of(new ProactiveTriggerEngine.UnreadPostDigest(9L, "anime", 3)));
        ProactiveTriggerEngine engine = new ProactiveTriggerEngine(
                characterStateService,
                postService,
                notificationService,
                experienceService,
                activityProvider,
                prompt -> "There are a few new anime notes in the warehouse.");
        when(experienceService.getRecentExperiences(3)).thenReturn(List.of());

        engine.checkTriggers();

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).send(org.mockito.ArgumentMatchers.eq(9L), notificationCaptor.capture());
        Notification notification = notificationCaptor.getValue();
        assertThat(notification.type()).isEqualTo("new-posts");
        assertThat(notification.channel()).isEqualTo(NotificationChannel.FLOATING);
    }

    private record StubActivityProvider(
            List<ProactiveTriggerEngine.AbsentUser> absentUsers,
            List<ProactiveTriggerEngine.UnreadPostDigest> unreadPostDigests
    ) implements ProactiveTriggerEngine.UserActivityProvider {
        @Override
        public List<ProactiveTriggerEngine.AbsentUser> findAbsentUsers(Duration threshold) {
            return absentUsers;
        }

        @Override
        public List<ProactiveTriggerEngine.UnreadPostDigest> findUnreadPostDigests(int minNewPosts) {
            return unreadPostDigests;
        }
    }
}
