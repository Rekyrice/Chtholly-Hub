package com.chtholly.agent.proactive;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationChannel;
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
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Handles proactive article digests, curation, unread content and rising posts. */
@Slf4j
@Service
@ConditionalOnExpression("${agent.extensions.proactive.enabled:true} && ${agent.extensions.experience.enabled:true} && ${agent.extensions.community-actions.enabled:true}")
public class ContentProactiveService {
    private static final String CURATION_PUSH_KEY = "agent:curation:last-push";
    private static final String RISING_STAR_KEY_PREFIX = "agent:rising-star:";

    private final PostService postService;
    private final SearchService searchService;
    private final CounterService counterService;
    private final ProactiveNotificationDispatcher dispatcher;
    private final SeedCurationReader curationReader;
    private final StringRedisTemplate redis;
    private final ProactiveTriggerEngine.UserActivityProvider activityProvider;
    private final CharacterStateUserActivityProvider characterActivityProvider;
    private final ProactiveAudienceService audienceService;
    private final ProactiveTextGenerator textGenerator;

    @Autowired
    ContentProactiveService(
            PostService postService,
            SearchService searchService,
            CounterService counterService,
            ProactiveNotificationDispatcher dispatcher,
            SeedCurationReader curationReader,
            StringRedisTemplate redis,
            ObjectProvider<ProactiveTriggerEngine.UserActivityProvider> activityProvider,
            ObjectProvider<CharacterStateUserActivityProvider> characterActivityProvider,
            ProactiveAudienceService audienceService,
            ObjectProvider<ChatClient> chatClientProvider) {
        this(postService, searchService, counterService, dispatcher, curationReader, redis,
                activityProvider.getIfAvailable(ProactiveTriggerEngine.NoopUserActivityProvider::new),
                characterActivityProvider.getIfAvailable(), audienceService,
                ProactiveTextGenerator.from(chatClientProvider));
    }

    ContentProactiveService(
            PostService postService,
            SearchService searchService,
            CounterService counterService,
            ProactiveNotificationDispatcher dispatcher,
            SeedCurationReader curationReader,
            StringRedisTemplate redis,
            ProactiveTriggerEngine.UserActivityProvider activityProvider,
            CharacterStateUserActivityProvider characterActivityProvider,
            ProactiveAudienceService audienceService,
            ProactiveTextGenerator textGenerator) {
        this.postService = postService;
        this.searchService = searchService;
        this.counterService = counterService;
        this.dispatcher = dispatcher;
        this.curationReader = curationReader;
        this.redis = redis;
        this.activityProvider = activityProvider;
        this.characterActivityProvider = characterActivityProvider;
        this.audienceService = audienceService;
        this.textGenerator = textGenerator;
    }

    /** Checks return briefings and followed-tag updates. */
    public void checkUnreadPosts() {
        generateReturnBriefings();
        postService.countSince(Duration.ofHours(6));
        for (ProactiveTriggerEngine.UnreadPostDigest digest
                : activityProvider.findUnreadPostDigests(ProactiveTriggerEngine.UNREAD_POST_THRESHOLD)) {
            if (digest == null || digest.userId() == null
                    || digest.postCount() < ProactiveTriggerEngine.UNREAD_POST_THRESHOLD
                    || dispatcher.totalSentToday(digest.userId()) >= ProactiveRateLimiter.DAILY_LIMIT) {
                continue;
            }
            String message = textGenerator.generate("""
                    你是珂朵莉。用户关注的标签下有几篇新文章。
                    用一句话温和地提醒，可以轻轻推荐，不要催促。
                    标签：%s
                    新文章数量：%d
                    """.formatted(digest.tagName(), digest.postCount()));
            if (message != null && !message.isBlank()) {
                dispatcher.send(digest.userId(),
                        new Notification("new-posts", message.trim(), NotificationChannel.FLOATING),
                        ProactiveNotificationDispatcher.Category.RECOMMEND);
            }
        }
    }

    public void sendDailyHotDigest() {
        List<FeedItemResponse> hot = searchService.recommendHot(
                Set.of(), ProactiveTriggerEngine.HOT_DIGEST_SIZE, null);
        String titles = titles(hot);
        if (titles.isBlank()) {
            return;
        }
        String message = "今天仓库里最热闹的 " + hot.size() + " 个话题：" + titles;
        sendToActiveUsers("hot-digest", message, ProactiveNotificationDispatcher.Category.GREET);
    }

    public void pushWeeklyCuration() {
        SeedCuration curation = curationReader.getLatest();
        if (curation == null || curation.collectionNote() == null || curation.collectionNote().isBlank()) {
            return;
        }
        String lastPush = redis.opsForValue().get(CURATION_PUSH_KEY);
        if (curation.curatedAt() != null && lastPush != null) {
            try {
                if (!curation.curatedAt().isAfter(Instant.parse(lastPush))) {
                    return;
                }
            } catch (Exception exception) {
                log.debug("Failed to parse the last curation push timestamp: {}", exception.getMessage());
            }
        }
        sendToActiveUsers("weekly-curation", "本周珂朵莉精选：" + curation.collectionNote(),
                ProactiveNotificationDispatcher.Category.RECOMMEND);
        if (curation.curatedAt() != null) {
            redis.opsForValue().set(CURATION_PUSH_KEY, curation.curatedAt().toString());
        }
    }

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
            long count = likes.getOrDefault(post.id(), 0L);
            if (count < ProactiveTriggerEngine.RISING_STAR_MIN_LIKES
                    || count < average * ProactiveTriggerEngine.RISING_STAR_MULTIPLIER
                    || Boolean.TRUE.equals(redis.hasKey(RISING_STAR_KEY_PREFIX + post.id()))) {
                continue;
            }
            redis.opsForValue().set(RISING_STAR_KEY_PREFIX + post.id(), Instant.now().toString(), Duration.ofDays(30));
            String title = post.title() == null ? "一篇文章" : post.title();
            sendToActiveUsers("rising-star", "这篇文章好像很受欢迎呢：" + title,
                    ProactiveNotificationDispatcher.Category.OBSERVATION);
        }
    }

    private void generateReturnBriefings() {
        List<ProactiveTriggerEngine.AbsentUser> candidates = characterActivityProvider == null
                ? List.of() : characterActivityProvider.findReturnBriefingCandidates();
        for (ProactiveTriggerEngine.AbsentUser user : candidates) {
            if (user == null || user.userId() == null
                    || dispatcher.totalSentToday(user.userId()) >= ProactiveRateLimiter.DAILY_LIMIT) {
                continue;
            }
            long newPosts = postService.countSince(ProactiveTriggerEngine.RETURN_BRIEFING_THRESHOLD);
            String hotTitles = titles(searchService.recommendHot(
                    Set.of(), ProactiveTriggerEngine.HOT_DIGEST_SIZE, user.userId()));
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
            dispatcher.send(user.userId(),
                    new Notification("return-briefing", message.trim(), NotificationChannel.FLOATING),
                    ProactiveNotificationDispatcher.Category.GREET);
        }
    }

    private void sendToActiveUsers(
            String type, String message, ProactiveNotificationDispatcher.Category category) {
        for (Long userId : audienceService.activeUserIds()) {
            if (userId != null && dispatcher.totalSentToday(userId) < ProactiveRateLimiter.DAILY_LIMIT) {
                dispatcher.send(userId, new Notification(type, message, NotificationChannel.FLOATING), category);
            }
        }
    }

    private Map<Long, Long> loadLikeCounts(List<PostSummary> posts) {
        Map<Long, Long> likes = new HashMap<>();
        for (PostSummary post : posts) {
            if (post != null && post.id() != null) {
                Map<String, Long> counts = counterService.getCounts(
                        "post", String.valueOf(post.id()), List.of("like"));
                likes.put(post.id(), counts.getOrDefault("like", 0L));
            }
        }
        return likes;
    }

    private String titles(List<FeedItemResponse> posts) {
        if (posts == null) {
            return "";
        }
        return posts.stream().map(FeedItemResponse::title)
                .filter(title -> title != null && !title.isBlank())
                .collect(Collectors.joining("、"));
    }
}
