package com.chtholly.agent.proactive;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationChannel;
import com.chtholly.post.service.PostService;
import com.chtholly.recommendation.UserInterestProfile;
import com.chtholly.recommendation.UserSimilarityService;
import com.chtholly.recommendation.model.InterestProfile;
import com.chtholly.recommendation.model.SimilarUser;
import com.chtholly.user.domain.User;
import com.chtholly.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Handles interest-based introductions and new-resident greetings. */
@Slf4j
@Service
@ConditionalOnExpression("${agent.extensions.proactive.enabled:true} && ${agent.extensions.experience.enabled:true} && ${agent.extensions.community-actions.enabled:true}")
public class SocialProactiveService {
    private static final String INTEREST_MATCH_KEY_PREFIX = "agent:interest-match:";
    private static final String NEW_RESIDENT_KEY_PREFIX = "agent:new-resident-intro:";

    private final UserSimilarityService similarityService;
    private final UserInterestProfile interestProfile;
    private final UserService userService;
    private final PostService postService;
    private final ProactiveNotificationDispatcher dispatcher;
    private final StringRedisTemplate redis;
    private final ProactiveAudienceService audienceService;
    private final ProactiveTextGenerator textGenerator;

    @Autowired
    SocialProactiveService(
            ObjectProvider<UserSimilarityService> similarityService,
            ObjectProvider<UserInterestProfile> interestProfile,
            ObjectProvider<UserService> userService,
            PostService postService,
            ProactiveNotificationDispatcher dispatcher,
            StringRedisTemplate redis,
            ProactiveAudienceService audienceService,
            ObjectProvider<ChatClient> chatClientProvider) {
        this(similarityService.getIfAvailable(), interestProfile.getIfAvailable(), userService.getIfAvailable(),
                postService, dispatcher, redis, audienceService, ProactiveTextGenerator.from(chatClientProvider));
    }

    SocialProactiveService(
            UserSimilarityService similarityService,
            UserInterestProfile interestProfile,
            UserService userService,
            PostService postService,
            ProactiveNotificationDispatcher dispatcher,
            StringRedisTemplate redis,
            ProactiveAudienceService audienceService,
            ProactiveTextGenerator textGenerator) {
        this.similarityService = similarityService;
        this.interestProfile = interestProfile;
        this.userService = userService;
        this.postService = postService;
        this.dispatcher = dispatcher;
        this.redis = redis;
        this.audienceService = audienceService;
        this.textGenerator = textGenerator;
    }

    public void detectInterestMatches() {
        if (similarityService == null) {
            return;
        }
        Set<Long> active = new HashSet<>(audienceService.activeUserIds());
        if (active.size() < 2) {
            return;
        }
        List<Long> users = similarityService.listProfileUserIds().stream()
                .filter(active::contains).sorted().toList();
        for (Long userId : users) {
            for (SimilarUser candidate : similarityService.findSimilarUsers(
                    userId, ProactiveTriggerEngine.INTEREST_MATCH_TOP_K)) {
                if (!isEligiblePair(userId, candidate, active)) {
                    continue;
                }
                long otherId = candidate.userId();
                List<String> commonTags = similarityService.commonInterestTags(
                        userId, otherId, UserSimilarityService.COMMON_TAG_MIN_WEIGHT);
                if (commonTags.isEmpty()) {
                    continue;
                }
                String tags = formatTagPhrase(commonTags);
                boolean sentA = sendInterestMatch(userId, resolveNickname(otherId), tags);
                boolean sentB = sendInterestMatch(otherId, resolveNickname(userId), tags);
                if (sentA || sentB) {
                    redis.opsForValue().set(interestMatchKey(userId, otherId),
                            Instant.now().toString(), Duration.ofDays(30));
                    log.info("interest-match bridge userA={} userB={} similarity={} tags={}",
                            userId, otherId, candidate.similarity(), tags);
                }
            }
        }
    }

    public void introduceNewResidents() {
        List<Long> newcomers = postService.listFirstTimePublisherIds(ProactiveTriggerEngine.NEW_RESIDENT_WINDOW);
        if (newcomers == null || newcomers.isEmpty()) {
            return;
        }
        List<Long> active = audienceService.activeUserIds();
        for (Long newcomerId : newcomers) {
            if (newcomerId == null || Boolean.TRUE.equals(redis.hasKey(NEW_RESIDENT_KEY_PREFIX + newcomerId))) {
                continue;
            }
            String message = buildNewResidentMessage(
                    resolveNickname(newcomerId), resolveInterestTagPhrase(newcomerId));
            for (Long recipientId : active) {
                if (recipientId == null || recipientId.equals(newcomerId)
                        || dispatcher.totalSentToday(recipientId) >= ProactiveRateLimiter.DAILY_LIMIT) {
                    continue;
                }
                dispatcher.send(recipientId,
                        new Notification("new-resident", message, NotificationChannel.FLOATING),
                        ProactiveNotificationDispatcher.Category.GREET);
            }
            // 即使门控拦住了通知也记录介绍，避免每天重复处理同一位新居民。
            redis.opsForValue().set(NEW_RESIDENT_KEY_PREFIX + newcomerId,
                    Instant.now().toString(), Duration.ofDays(7));
        }
    }

    private boolean isEligiblePair(Long userId, SimilarUser candidate, Set<Long> active) {
        if (candidate == null || candidate.similarity() <= UserSimilarityService.INTEREST_MATCH_THRESHOLD) {
            return false;
        }
        long otherId = candidate.userId();
        return userId < otherId && active.contains(otherId)
                && !Boolean.TRUE.equals(redis.hasKey(interestMatchKey(userId, otherId)));
    }

    private boolean sendInterestMatch(long recipientId, String nickname, String tags) {
        if (dispatcher.totalSentToday(recipientId) >= ProactiveRateLimiter.DAILY_LIMIT) {
            return false;
        }
        return dispatcher.send(recipientId,
                new Notification("interest-match", buildInterestMatchMessage(nickname, tags),
                        NotificationChannel.FLOATING),
                ProactiveNotificationDispatcher.Category.RECOMMEND);
    }

    private String buildInterestMatchMessage(String nickname, String tags) {
        String who = nickname == null || nickname.isBlank() ? "一位朋友" : nickname;
        String generated = textGenerator.generate("""
                你是珂朵莉。请告诉用户：仓库里有一位也喜欢「%s」的人（对方昵称：%s）。
                用第一人称写一句温和但不刻意的搭桥话，不要催促对方去认识，不要夸张。
                只输出这一句话，不要引号。
                """.formatted(tags, who));
        return generated == null || generated.isBlank()
                ? "悄悄告诉你，" + who + " 好像也喜欢「" + tags + "」呢。"
                : generated.trim();
    }

    private String buildNewResidentMessage(String nickname, String tags) {
        String who = nickname == null || nickname.isBlank() ? "一位新朋友" : nickname;
        String generated = textGenerator.generate("""
                你是珂朵莉。仓库来了一位新居民「%s」，TA 好像很喜欢「%s」。
                用第一人称写一句温柔的介绍，像轻轻提起一件小事，不要推销。
                只输出这一句话，不要引号。
                """.formatted(who, tags));
        return generated == null || generated.isBlank()
                ? "仓库来了新朋友，" + who + " 好像很喜欢「" + tags + "」呢。"
                : generated.trim();
    }

    private String resolveInterestTagPhrase(long userId) {
        if (interestProfile != null) {
            InterestProfile profile = interestProfile.buildProfile(userId);
            if (profile != null && profile.tagWeights() != null && !profile.tagWeights().isEmpty()) {
                List<String> tags = profile.tagWeights().entrySet().stream()
                        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                        .limit(3).map(Map.Entry::getKey).toList();
                if (!tags.isEmpty()) {
                    return formatTagPhrase(tags);
                }
            }
        }
        return "写点什么";
    }

    private String resolveNickname(long userId) {
        if (userService == null) {
            return "一位朋友";
        }
        return userService.findById(userId).map(User::getNickname)
                .filter(name -> name != null && !name.isBlank()).orElse("一位朋友");
    }

    private static String formatTagPhrase(List<String> tags) {
        List<String> trimmed = new ArrayList<>();
        for (String tag : tags == null ? List.<String>of() : tags) {
            if (tag != null && !tag.isBlank()) {
                trimmed.add(tag.trim());
            }
            if (trimmed.size() == 3) {
                break;
            }
        }
        return trimmed.isEmpty() ? "一些相似的东西" : String.join("、", trimmed);
    }

    static String interestMatchKey(long userA, long userB) {
        return INTEREST_MATCH_KEY_PREFIX + Math.min(userA, userB) + ":" + Math.max(userA, userB);
    }
}
