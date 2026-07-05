package com.chtholly.agent.proactive;

import com.chtholly.agent.cognitive.ExperienceService;
import com.chtholly.agent.cognitive.Observation;
import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationChannel;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.post.service.PostService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Checks whether Chtholly should gently initiate an in-site interaction.
 */
@Slf4j
@Service
public class ProactiveTriggerEngine {

    private static final Duration ABSENT_THRESHOLD = Duration.ofDays(3);
    private static final int UNREAD_POST_THRESHOLD = 2;

    private final CharacterStateService characterStateService;
    private final PostService postService;
    private final NotificationService notificationService;
    private final ExperienceService experienceService;
    private final UserActivityProvider activityProvider;
    private final TextGenerator textGenerator;

    @Autowired
    public ProactiveTriggerEngine(CharacterStateService characterStateService,
                                  PostService postService,
                                  NotificationService notificationService,
                                  ExperienceService experienceService,
                                  ObjectProvider<UserActivityProvider> activityProviderProvider,
                                  ObjectProvider<ChatClient> chatClientProvider) {
        this(characterStateService,
                postService,
                notificationService,
                experienceService,
                activityProviderProvider.getIfAvailable(NoopUserActivityProvider::new),
                prompt -> generateWithChatClient(chatClientProvider.getIfAvailable(), prompt));
    }

    ProactiveTriggerEngine(CharacterStateService characterStateService,
                           PostService postService,
                           NotificationService notificationService,
                           ExperienceService experienceService,
                           UserActivityProvider activityProvider,
                           TextGenerator textGenerator) {
        this.characterStateService = characterStateService;
        this.postService = postService;
        this.notificationService = notificationService;
        this.experienceService = experienceService;
        this.activityProvider = activityProvider == null ? new NoopUserActivityProvider() : activityProvider;
        this.textGenerator = textGenerator;
    }

    /**
     * Check for proactive triggers every 2 hours.
     */
    @Scheduled(fixedDelay = 7_200_000L, initialDelay = 300_000L)
    public void checkTriggers() {
        // 先保留依赖触点：后续活跃用户索引接入 CharacterStateService 时无需重塑引擎结构。
        characterStateService.getMoodBaseline();
        generateMissingYouNotifications();
        checkUnreadPosts();
        generateThoughts();
    }

    private void generateMissingYouNotifications() {
        for (AbsentUser user : activityProvider.findAbsentUsers(ABSENT_THRESHOLD)) {
            if (user == null || user.userId() == null) {
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
                notificationService.send(user.userId(), new Notification(
                        "missing-you",
                        message.trim(),
                        NotificationChannel.FLOATING));
            }
        }
    }

    private void checkUnreadPosts() {
        postService.countSince(Duration.ofHours(6));
        for (UnreadPostDigest digest : activityProvider.findUnreadPostDigests(UNREAD_POST_THRESHOLD)) {
            if (digest == null || digest.userId() == null || digest.postCount() < UNREAD_POST_THRESHOLD) {
                continue;
            }
            String message = textGenerator.generate("""
                    你是珂朵莉。用户关注的标签下有几篇新文章。
                    用一句话温和地提醒，可以轻轻推荐，不要催促。
                    标签：%s
                    新文章数量：%d
                    """.formatted(digest.tagName(), digest.postCount()));
            if (message != null && !message.isBlank()) {
                notificationService.send(digest.userId(), new Notification(
                        "new-posts",
                        message.trim(),
                        NotificationChannel.FLOATING));
            }
        }
    }

    private void generateThoughts() {
        List<Observation> recent = experienceService.getRecentExperiences(3);
        if (recent == null || recent.isEmpty()) {
            return;
        }
        recent.stream()
                .filter(observation -> observation != null && observation.text() != null && !observation.text().isBlank())
                .max(Comparator.comparingDouble(Observation::valueScore))
                .ifPresent(best -> notificationService.broadcast(new Notification(
                        "thought",
                        best.text(),
                        best.createdAt(),
                        NotificationChannel.AGENT_PAGE)));
    }

    private static String generateWithChatClient(ChatClient chatClient, String prompt) {
        if (chatClient == null) {
            return "";
        }
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    /**
     * Supplies indexed user activity data for proactive checks.
     */
    public interface UserActivityProvider {
        List<AbsentUser> findAbsentUsers(Duration threshold);

        List<UnreadPostDigest> findUnreadPostDigests(int minNewPosts);
    }

    public record AbsentUser(Long userId, String nickname, Instant lastInteraction) {
    }

    public record UnreadPostDigest(Long userId, String tagName, int postCount) {
    }

    @FunctionalInterface
    interface TextGenerator {
        String generate(String prompt);
    }

    private static final class NoopUserActivityProvider implements UserActivityProvider {
        @Override
        public List<AbsentUser> findAbsentUsers(Duration threshold) {
            return List.of();
        }

        @Override
        public List<UnreadPostDigest> findUnreadPostDigests(int minNewPosts) {
            return List.of();
        }
    }
}
