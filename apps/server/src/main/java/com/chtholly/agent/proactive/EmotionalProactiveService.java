package com.chtholly.agent.proactive;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import com.chtholly.agent.cognitive.ExperienceService;
import com.chtholly.agent.cognitive.Observation;
import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationChannel;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.agent.state.CharacterStateService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Handles emotionally motivated proactive messages and ambient thoughts. */
@Service
@ConditionalOnExpression("${agent.extensions.proactive.enabled:true} && ${agent.extensions.experience.enabled:true} && ${agent.extensions.community-actions.enabled:true}")
public class EmotionalProactiveService {
    private final CharacterStateService characterStateService;
    private final NotificationService notificationService;
    private final ProactiveNotificationDispatcher dispatcher;
    private final ExperienceService experienceService;
    private final ProactiveTriggerEngine.UserActivityProvider activityProvider;
    private final ProactiveTextGenerator textGenerator;

    @Autowired
    EmotionalProactiveService(
            CharacterStateService characterStateService,
            NotificationService notificationService,
            ProactiveNotificationDispatcher dispatcher,
            ExperienceService experienceService,
            ObjectProvider<ProactiveTriggerEngine.UserActivityProvider> activityProvider,
            ObjectProvider<ChatClient> chatClientProvider) {
        this(characterStateService, notificationService, dispatcher, experienceService,
                activityProvider.getIfAvailable(ProactiveTriggerEngine.NoopUserActivityProvider::new),
                ProactiveTextGenerator.from(chatClientProvider));
    }

    EmotionalProactiveService(
            CharacterStateService characterStateService,
            NotificationService notificationService,
            ProactiveNotificationDispatcher dispatcher,
            ExperienceService experienceService,
            ProactiveTriggerEngine.UserActivityProvider activityProvider,
            ProactiveTextGenerator textGenerator) {
        this.characterStateService = characterStateService;
        this.notificationService = notificationService;
        this.dispatcher = dispatcher;
        this.experienceService = experienceService;
        this.activityProvider = activityProvider;
        this.textGenerator = textGenerator;
    }

    /** Runs emotional absence checks and publishes the strongest recent thought. */
    public void checkTriggers() {
        characterStateService.getMoodBaseline();
        generateMissingYouNotifications();
        generateThoughts();
    }

    private void generateMissingYouNotifications() {
        for (ProactiveTriggerEngine.AbsentUser user
                : activityProvider.findAbsentUsers(ProactiveTriggerEngine.ABSENT_THRESHOLD)) {
            if (user == null || user.userId() == null || isLongAbsent(user.lastInteraction())) {
                continue;
            }
            if (dispatcher.totalSentToday(user.userId()) >= ProactiveRateLimiter.DAILY_LIMIT) {
                continue;
            }
            String message = textGenerator.generate("""
                    你是珂朵莉。有一个朋友已经 3 天没来仓库了。
                    用第一人称写一句关心的话，不要过于热情，也不要责备。
                    风格：安静地想念、温和地关心。
                    用户名：%s
                    上次互动：%s
                    """.formatted(user.nickname(), user.lastInteraction()));
            if (message != null && !message.isBlank()) {
                dispatcher.send(
                        user.userId(),
                        new Notification("missing-you", message.trim(), NotificationChannel.FLOATING),
                        ProactiveNotificationDispatcher.Category.GREET);
            }
        }
    }

    private void generateThoughts() {
        List<Observation> recent = experienceService.getRecentExperiences(3);
        if (recent == null || recent.isEmpty()) {
            return;
        }
        recent.stream()
                .filter(item -> item != null && item.text() != null && !item.text().isBlank())
                .max(Comparator.comparingDouble(Observation::valueScore))
                .ifPresent(best -> notificationService.broadcast(new Notification(
                        "thought", best.text(), best.createdAt(), NotificationChannel.AGENT_PAGE)));
    }

    private boolean isLongAbsent(Instant lastInteraction) {
        return lastInteraction != null
                && lastInteraction.isBefore(Instant.now().minus(ProactiveTriggerEngine.RETURN_BRIEFING_THRESHOLD));
    }
}
