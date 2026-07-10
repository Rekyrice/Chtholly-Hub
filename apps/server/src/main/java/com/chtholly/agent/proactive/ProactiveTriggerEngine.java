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
import com.chtholly.recommendation.UserInterestProfile;
import com.chtholly.recommendation.UserSimilarityService;
import com.chtholly.recommendation.model.InterestProfile;
import com.chtholly.recommendation.model.SimilarUser;
import com.chtholly.search.service.SearchService;
import com.chtholly.seed.SeedCuration;
import com.chtholly.user.domain.User;
import com.chtholly.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 珂朵莉主动触发引擎：信息主动 + 编辑主动 + 情感主动 + 社交主动。
 */
@Slf4j
@Service
public class ProactiveTriggerEngine {

    static final Duration ABSENT_THRESHOLD = Duration.ofDays(3);
    static final Duration RETURN_BRIEFING_THRESHOLD = Duration.ofDays(7);
    static final Duration ACTIVE_USER_WINDOW = Duration.ofDays(7);
    static final Duration NEW_RESIDENT_WINDOW = Duration.ofDays(7);
    static final int UNREAD_POST_THRESHOLD = 2;
    static final int HOT_DIGEST_SIZE = 3;
    static final int INTEREST_MATCH_TOP_K = 20;
    static final double RISING_STAR_MULTIPLIER = 3.0;
    static final long RISING_STAR_MIN_LIKES = 3L;

    private static final String CURATION_PUSH_KEY = "agent:curation:last-push";
    private static final String RISING_STAR_KEY_PREFIX = "agent:rising-star:";
    private static final String INTEREST_MATCH_KEY_PREFIX = "agent:interest-match:";
    private static final String NEW_RESIDENT_INTRO_KEY_PREFIX = "agent:new-resident-intro:";
    private static final Duration INTEREST_MATCH_TTL = Duration.ofDays(30);
    private static final Duration NEW_RESIDENT_INTRO_TTL = Duration.ofDays(7);

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
    private final UserSimilarityService userSimilarityService;
    private final UserInterestProfile userInterestProfile;
    private final UserService userService;

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
                                  ObjectProvider<ChatClient> chatClientProvider,
                                  ObjectProvider<UserSimilarityService> userSimilarityServiceProvider,
                                  ObjectProvider<UserInterestProfile> userInterestProfileProvider,
                                  ObjectProvider<UserService> userServiceProvider) {
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
                prompt -> generateWithChatClient(chatClientProvider.getIfAvailable(), prompt),
                userSimilarityServiceProvider.getIfAvailable(),
                userInterestProfileProvider.getIfAvailable(),
                userServiceProvider.getIfAvailable());
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
                           TextGenerator textGenerator,
                           UserSimilarityService userSimilarityService,
                           UserInterestProfile userInterestProfile,
                           UserService userService) {
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
        this.userSimilarityService = userSimilarityService;
        this.userInterestProfile = userInterestProfile;
        this.userService = userService;
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
            } catch (Exception e) {
                log.debug("解析精选推送时间失败，继续推送: {}", e.getMessage());
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

    /**
     * 每天 10:00 / 18:00：兴趣高度重叠的活跃用户对，珂朵莉分别搭桥介绍。
     */
    @Scheduled(cron = "0 0 10,18 * * *")
    public void detectInterestMatches() {
        if (userSimilarityService == null) {
            return;
        }
        Set<Long> active = new HashSet<>(activeUserIds());
        if (active.size() < 2) {
            return;
        }
        List<Long> profileUsers = userSimilarityService.listProfileUserIds().stream()
                .filter(active::contains)
                .sorted()
                .toList();
        if (profileUsers.size() < 2) {
            return;
        }

        int matchedPairs = 0;
        for (Long userId : profileUsers) {
            List<SimilarUser> similar = userSimilarityService.findSimilarUsers(userId, INTEREST_MATCH_TOP_K);
            for (SimilarUser candidate : similar) {
                if (candidate == null || candidate.similarity() <= UserSimilarityService.INTEREST_MATCH_THRESHOLD) {
                    continue;
                }
                long otherId = candidate.userId();
                // 只处理有序对，避免 A↔B 重复推送
                if (userId >= otherId || !active.contains(otherId)) {
                    continue;
                }
                if (isInterestMatchMarked(userId, otherId)) {
                    continue;
                }

                List<String> commonTags = userSimilarityService.commonInterestTags(
                        userId, otherId, UserSimilarityService.COMMON_TAG_MIN_WEIGHT);
                if (commonTags.isEmpty()) {
                    continue;
                }
                String tagPhrase = formatTagPhrase(commonTags);
                String nicknameA = resolveNickname(userId);
                String nicknameB = resolveNickname(otherId);

                boolean sentA = sendInterestMatch(userId, nicknameB, tagPhrase);
                boolean sentB = sendInterestMatch(otherId, nicknameA, tagPhrase);
                if (sentA || sentB) {
                    markInterestMatch(userId, otherId);
                    matchedPairs++;
                    log.info("interest-match 搭桥 userA={} userB={} similarity={} tags={}",
                            userId, otherId, candidate.similarity(), tagPhrase);
                }
            }
        }
        if (matchedPairs > 0) {
            log.info("interest-match 本轮完成 pairs={}", matchedPairs);
        }
    }

    /**
     * 每天 12:00：向活跃用户介绍近 7 天首次发文的新居民。
     */
    @Scheduled(cron = "0 0 12 * * *")
    public void introduceNewResidents() {
        List<Long> newcomers = postService.listFirstTimePublisherIds(NEW_RESIDENT_WINDOW);
        if (newcomers == null || newcomers.isEmpty()) {
            return;
        }
        List<Long> active = activeUserIds();
        if (active.isEmpty()) {
            return;
        }

        int introduced = 0;
        for (Long newUserId : newcomers) {
            if (newUserId == null || isNewResidentIntroduced(newUserId)) {
                continue;
            }
            String nickname = resolveNickname(newUserId);
            String tagPhrase = resolveInterestTagPhrase(newUserId);
            String message = buildNewResidentMessage(nickname, tagPhrase);

            boolean anySent = false;
            for (Long recipientId : active) {
                if (recipientId == null || recipientId.equals(newUserId)) {
                    continue;
                }
                if (dispatcher.totalSentToday(recipientId) >= ProactiveRateLimiter.DAILY_LIMIT) {
                    continue;
                }
                boolean sent = dispatcher.send(
                        recipientId,
                        new Notification("new-resident", message, NotificationChannel.FLOATING),
                        ProactiveNotificationDispatcher.Category.GREET);
                anySent = anySent || sent;
            }
            // 无论门控是否挡住，都记一次介绍，避免每天反复轰炸同一新居民
            markNewResidentIntroduced(newUserId);
            if (anySent) {
                introduced++;
            }
        }
        if (introduced > 0) {
            log.info("new-resident 本轮介绍人数={}", introduced);
        }
    }

    private boolean sendInterestMatch(long recipientId, String otherNickname, String tagPhrase) {
        if (dispatcher.totalSentToday(recipientId) >= ProactiveRateLimiter.DAILY_LIMIT) {
            return false;
        }
        String message = buildInterestMatchMessage(otherNickname, tagPhrase);
        return dispatcher.send(
                recipientId,
                new Notification("interest-match", message, NotificationChannel.FLOATING),
                ProactiveNotificationDispatcher.Category.RECOMMEND);
    }

    private String buildInterestMatchMessage(String otherNickname, String tagPhrase) {
        String prompt = """
                你是珂朵莉。请告诉用户：仓库里有一位也喜欢「%s」的人（对方昵称：%s）。
                用第一人称写一句温柔但不刻意的搭桥话，不要催促对方去认识，不要夸张。
                只输出这一句话，不要引号。
                """.formatted(tagPhrase, otherNickname == null || otherNickname.isBlank() ? "一位朋友" : otherNickname);
        String generated = textGenerator == null ? null : textGenerator.generate(prompt);
        if (generated != null && !generated.isBlank()) {
            return generated.trim();
        }
        String who = otherNickname == null || otherNickname.isBlank() ? "有个人" : otherNickname;
        return "悄悄告诉你，" + who + " 好像也喜欢「" + tagPhrase + "」呢。";
    }

    private String buildNewResidentMessage(String nickname, String tagPhrase) {
        String who = nickname == null || nickname.isBlank() ? "一位新朋友" : nickname;
        String prompt = """
                你是珂朵莉。仓库来了新居民「%s」，TA 好像很喜欢「%s」。
                用第一人称写一句温柔的介绍，像轻轻提起一件小事，不要推销。
                只输出这一句话，不要引号。
                """.formatted(who, tagPhrase);
        String generated = textGenerator == null ? null : textGenerator.generate(prompt);
        if (generated != null && !generated.isBlank()) {
            return generated.trim();
        }
        return "仓库来了新朋友，" + who + " 好像很喜欢「" + tagPhrase + "」呢。";
    }

    private String resolveInterestTagPhrase(long userId) {
        if (userInterestProfile != null) {
            InterestProfile profile = userInterestProfile.buildProfile(userId);
            if (profile != null && profile.tagWeights() != null && !profile.tagWeights().isEmpty()) {
                List<String> top = profile.tagWeights().entrySet().stream()
                        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                        .limit(3)
                        .map(Map.Entry::getKey)
                        .toList();
                if (!top.isEmpty()) {
                    return formatTagPhrase(top);
                }
            }
        }
        return "写点什么";
    }

    private String resolveNickname(long userId) {
        if (userService == null) {
            return "一位朋友";
        }
        return userService.findById(userId)
                .map(User::getNickname)
                .filter(name -> name != null && !name.isBlank())
                .orElse("一位朋友");
    }

    private static String formatTagPhrase(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "一些相似的东西";
        }
        List<String> trimmed = new ArrayList<>();
        for (String tag : tags) {
            if (tag != null && !tag.isBlank()) {
                trimmed.add(tag.trim());
            }
            if (trimmed.size() >= 3) {
                break;
            }
        }
        if (trimmed.isEmpty()) {
            return "一些相似的东西";
        }
        return String.join("、", trimmed);
    }

    private boolean isInterestMatchMarked(long userA, long userB) {
        return Boolean.TRUE.equals(redis.hasKey(interestMatchKey(userA, userB)));
    }

    private void markInterestMatch(long userA, long userB) {
        redis.opsForValue().set(interestMatchKey(userA, userB), Instant.now().toString(), INTEREST_MATCH_TTL);
    }

    static String interestMatchKey(long userA, long userB) {
        long min = Math.min(userA, userB);
        long max = Math.max(userA, userB);
        return INTEREST_MATCH_KEY_PREFIX + min + ":" + max;
    }

    private boolean isNewResidentIntroduced(long userId) {
        return Boolean.TRUE.equals(redis.hasKey(NEW_RESIDENT_INTRO_KEY_PREFIX + userId));
    }

    private void markNewResidentIntroduced(long userId) {
        redis.opsForValue().set(
                NEW_RESIDENT_INTRO_KEY_PREFIX + userId,
                Instant.now().toString(),
                NEW_RESIDENT_INTRO_TTL);
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
