package com.chtholly.agent.proactive;

import com.chtholly.agent.cognitive.ExperienceService;
import com.chtholly.agent.cognitive.Observation;
import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationChannel;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.post.api.dto.PostSummary;
import com.chtholly.post.service.PostService;
import com.chtholly.search.service.SearchService;
import com.chtholly.seed.SeedCuration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 珂朵莉主动触发引擎：信息主动 + 编辑主动 + 情感主动。
 */
@Slf4j
@Service
public class ProactiveTriggerEngine {

    static final Duration ABSENT_THRESHOLD = Duration.ofDays(3);
    static final Duration RETURN_BRIEFING_THRESHOLD = Duration.ofDays(7);
    static final Duration ACTIVE_USER_WINDOW = Duration.ofDays(7);
    static final int UNREAD_POST_THRESHOLD = 2;
    static final int HOT_DIGEST_SIZE = 3;
    static final double RISING_STAR_MULTIPLIER = 3.0;
    static final long RISING_STAR_MIN_LIKES = 3L;

    private static final String CURATION_PUSH_KEY = "agent:curation:last-push";
    private static final String RISING_STAR_KEY_PREFIX = "agent:rising-star:";

    private final CharacterStateService characterStateService;
    private final PostService postService;
    private final NotificationService notificationService;
    private final ProactiveNotificationDispatcher dispatcher;
    private final ExperienceService experienceService;
    private final SearchService searchService;
    private final CounterService counterService;
    private final SeedCurationReader curationReader;
    private final StringRedisTemplate redis;
    private final UserActivityProvider activityProvider;
    private final CharacterStateUserActivityProvider characterActivityProvider;
    private final TextGenerator textGenerator;

    @Autowired
    public ProactiveTriggerEngine(CharacterStateService characterStateService,
                                  PostService postService,
                                  NotificationService notificationService,
                                  ProactiveNotificationDispatcher dispatcher,
                                  ExperienceService experienceService,
                                  SearchService searchService,
                                  CounterService counterService,
                                  SeedCurationReader curationReader,
                                  StringRedisTemplate redis,
                                  ObjectProvider<UserActivityProvider> activityProviderProvider,
                                  ObjectProvider<CharacterStateUserActivityProvider> characterActivityProvider,
                                  ObjectProvider<ChatClient> chatClientProvider) {
        this(characterStateService,
                postService,
                notificationService,
                dispatcher,
                experienceService,
                searchService,
                counterService,
                curationReader,
                redis,
                activityProviderProvider.getIfAvailable(NoopUserActivityProvider::new),
                characterActivityProvider.getIfAvailable(),
                prompt -> generateWithChatClient(chatClientProvider.getIfAvailable(), prompt));
    }

    ProactiveTriggerEngine(CharacterStateService characterStateService,
                           PostService postService,
                           NotificationService notificationService,
                           ProactiveNotificationDispatcher dispatcher,
                           ExperienceService experienceService,
                           SearchService searchService,
                           CounterService counterService,
                           SeedCurationReader curationReader,
                           StringRedisTemplate redis,
                           UserActivityProvider activityProvider,
                           CharacterStateUserActivityProvider characterActivityProvider,
                           TextGenerator textGenerator) {
        this.characterStateService = characterStateService;
        this.postService = postService;
        this.notificationService = notificationService;
        this.dispatcher = dispatcher;
        this.experienceService = experienceService;
        this.searchService = searchService;
        this.counterService = counterService;
        this.curationReader = curationReader;
        this.redis = redis;
        this.activityProvider = activityProvider == null ? new NoopUserActivityProvider() : activityProvider;
        this.characterActivityProvider = characterActivityProvider;
        this.textGenerator = textGenerator;
    }

    /**
     * 每 2 小时检查情感/信息类触发器。
     */
    @Scheduled(fixedDelay = 7_200_000L, initialDelay = 300_000L)
    public void checkTriggers() {
        characterStateService.getMoodBaseline();
        generateMissingYouNotifications();
        generateReturnBriefings();
        checkUnreadPosts();
        generateThoughts();
    }

    /**
     * 每天 20:00 向活跃用户推送热门摘要。
     */
    @Scheduled(cron = "0 0 20 * * *")
    public void sendDailyHotDigest() {
        List<FeedItemResponse> hot = searchService.recommendHot(Set.of(), HOT_DIGEST_SIZE, null);
        if (hot.isEmpty()) {
            return;
        }
        String titles = hot.stream()
                .map(FeedItemResponse::title)
                .filter(title -> title != null && !title.isBlank())
                .collect(Collectors.joining("、"));
        if (titles.isBlank()) {
            return;
        }
        String message = "今天仓库里最热闹的 " + hot.size() + " 个话题：" + titles;
        for (Long userId : activeUserIds()) {
            if (dispatcher.totalSentToday(userId) >= ProactiveRateLimiter.DAILY_LIMIT) {
                continue;
            }
            dispatcher.send(
                    userId,
                    new Notification("hot-digest", message, NotificationChannel.FLOATING),
                    ProactiveNotificationDispatcher.Category.GREET);
        }
    }

    /**
     * 每周一 10:30 推送最新珂朵莉精选。
     */
    @Scheduled(cron = "0 30 10 * * 1")
    public void pushWeeklyCuration() {
        SeedCuration curation = curationReader.getLatest();
        if (curation == null || curation.collectionNote() == null || curation.collectionNote().isBlank()) {
            return;
        }
        String lastPush = redis.opsForValue().get(CURATION_PUSH_KEY);
        if (curation.curatedAt() != null && lastPush != null) {
            try {
                Instant pushedAt = Instant.parse(lastPush);
                if (!curation.curatedAt().isAfter(pushedAt)) {
                    return;
                }
            } catch (Exception ignored) {
                // 继续推送
            }
        }
        String message = "本周珂朵莉精选：" + curation.collectionNote();
        for (Long userId : activeUserIds()) {
            if (dispatcher.totalSentToday(userId) >= ProactiveRateLimiter.DAILY_LIMIT) {
                continue;
            }
            dispatcher.send(
                    userId,
                    new Notification("weekly-curation", message, NotificationChannel.FLOATING),
                    ProactiveNotificationDispatcher.Category.RECOMMEND);
        }
        if (curation.curatedAt() != null) {
            redis.opsForValue().set(CURATION_PUSH_KEY, curation.curatedAt().toString());
        }
    }

    /**
     * 每 30 分钟检测新星文章并推送。
     */
    @Scheduled(cron = "0 */30 * * * *")
    public void detectRisingStars() {
        List<PostSummary> recent = postService.getRecentPosts(Duration.ofHours(24));
        if (recent.isEmpty()) {
            return;
        }
        Map<Long, Long> likes = loadLikeCounts(recent);
        double average = likes.values().stream().mapToLong(Long::longValue).average().orElse(0.0);
        if (average <= 0) {
            return;
        }
        for (PostSummary post : recent) {
            if (post == null || post.id() == null) {
                continue;
            }
            long likeCount = likes.getOrDefault(post.id(), 0L);
            if (likeCount < RISING_STAR_MIN_LIKES || likeCount < average * RISING_STAR_MULTIPLIER) {
                continue;
            }
            if (isRisingStarMarked(post.id())) {
                continue;
            }
            markRisingStar(post.id());
            String title = post.title() == null ? "一篇文章" : post.title();
            String message = "这篇文章好像很受欢迎呢：" + title;
            for (Long userId : activeUserIds()) {
                if (dispatcher.totalSentToday(userId) >= ProactiveRateLimiter.DAILY_LIMIT) {
                    continue;
                }
                dispatcher.send(
                        userId,
                        new Notification("rising-star", message, NotificationChannel.FLOATING),
                        ProactiveNotificationDispatcher.Category.OBSERVATION);
            }
        }
    }

    private void generateMissingYouNotifications() {
        for (AbsentUser user : activityProvider.findAbsentUsers(ABSENT_THRESHOLD)) {
            if (user == null || user.userId() == null) {
                continue;
            }
            if (isLongAbsent(user.lastInteraction())) {
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

    private void generateReturnBriefings() {
        List<AbsentUser> candidates = characterActivityProvider == null
                ? List.of()
                : characterActivityProvider.findReturnBriefingCandidates();
        for (AbsentUser user : candidates) {
            if (user == null || user.userId() == null) {
                continue;
            }
            if (dispatcher.totalSentToday(user.userId()) >= ProactiveRateLimiter.DAILY_LIMIT) {
                continue;
            }
            long newPosts = postService.countSince(RETURN_BRIEFING_THRESHOLD);
            List<FeedItemResponse> hot = searchService.recommendHot(Set.of(), HOT_DIGEST_SIZE, user.userId());
            String hotTitles = hot.stream()
                    .map(FeedItemResponse::title)
                    .filter(title -> title != null && !title.isBlank())
                    .collect(Collectors.joining("、"));
            String message = textGenerator.generate("""
                    你是珂朵莉。有一位朋友已经 7 天以上没有来仓库了。
                    请写一段回归简报，语气温柔、像 quietly 欢迎回来。
                    必须包含：新文章数量=%d，热门话题=%s
                    用户名：%s
                    上次互动：%s
                    """.formatted(newPosts, hotTitles.isBlank() ? "暂无" : hotTitles,
                    user.nickname(), user.lastInteraction()));
            if (message == null || message.isBlank()) {
                message = "你不在的这几天，仓库来了 %d 篇新文章。热门话题：%s"
                        .formatted(newPosts, hotTitles.isBlank() ? "还在慢慢热起来" : hotTitles);
            }
            dispatcher.send(
                    user.userId(),
                    new Notification("return-briefing", message.trim(), NotificationChannel.FLOATING),
                    ProactiveNotificationDispatcher.Category.GREET);
        }
    }

    private void checkUnreadPosts() {
        postService.countSince(Duration.ofHours(6));
        for (UnreadPostDigest digest : activityProvider.findUnreadPostDigests(UNREAD_POST_THRESHOLD)) {
            if (digest == null || digest.userId() == null || digest.postCount() < UNREAD_POST_THRESHOLD) {
                continue;
            }
            if (dispatcher.totalSentToday(digest.userId()) >= ProactiveRateLimiter.DAILY_LIMIT) {
                continue;
            }
            String message = textGenerator.generate("""
                    你是珂朵莉。用户关注的标签下有几篇新文章。
                    用一句话温和地提醒，可以轻轻推荐，不要催促。
                    标签：%s
                    新文章数量：%d
                    """.formatted(digest.tagName(), digest.postCount()));
            if (message != null && !message.isBlank()) {
                dispatcher.send(
                        digest.userId(),
                        new Notification("new-posts", message.trim(), NotificationChannel.FLOATING),
                        ProactiveNotificationDispatcher.Category.RECOMMEND);
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

    private List<Long> activeUserIds() {
        if (characterActivityProvider != null) {
            List<Long> active = characterActivityProvider.findActiveUserIds();
            if (!active.isEmpty()) {
                return active;
            }
        }
        Instant since = Instant.now().minus(ACTIVE_USER_WINDOW);
        return characterStateService.findUserIdsActiveSince(since);
    }

    private Map<Long, Long> loadLikeCounts(List<PostSummary> posts) {
        Map<Long, Long> likes = new HashMap<>();
        for (PostSummary post : posts) {
            if (post.id() == null) {
                continue;
            }
            Map<String, Long> counts = counterService.getCounts(
                    "post", String.valueOf(post.id()), List.of("like"));
            likes.put(post.id(), counts.getOrDefault("like", 0L));
        }
        return likes;
    }

    private boolean isLongAbsent(Instant lastInteraction) {
        if (lastInteraction == null) {
            return false;
        }
        return lastInteraction.isBefore(Instant.now().minus(RETURN_BRIEFING_THRESHOLD));
    }

    private boolean isRisingStarMarked(long postId) {
        return Boolean.TRUE.equals(redis.hasKey(RISING_STAR_KEY_PREFIX + postId));
    }

    private void markRisingStar(long postId) {
        redis.opsForValue().set(RISING_STAR_KEY_PREFIX + postId, Instant.now().toString(), Duration.ofDays(30));
    }

    int totalSentToday(long userId) {
        return dispatcher.totalSentToday(userId);
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
