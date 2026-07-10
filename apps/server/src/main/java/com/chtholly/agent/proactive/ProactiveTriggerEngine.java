package com.chtholly.agent.proactive;

import com.chtholly.agent.cognitive.ExperienceService;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.service.PostService;
import com.chtholly.recommendation.UserInterestProfile;
import com.chtholly.recommendation.UserSimilarityService;
import com.chtholly.search.service.SearchService;
import com.chtholly.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scheduling facade for proactive behavior domains.
 *
 * <p>Business decisions live in emotional, content and social services. This class owns only
 * cron entrypoints so task monitoring and distributed-lock names remain stable.
 */
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

    private final EmotionalProactiveService emotionalService;
    private final ContentProactiveService contentService;
    private final SocialProactiveService socialService;

    @Autowired
    public ProactiveTriggerEngine(
            EmotionalProactiveService emotionalService,
            ContentProactiveService contentService,
            SocialProactiveService socialService) {
        this.emotionalService = emotionalService;
        this.contentService = contentService;
        this.socialService = socialService;
    }

    /** Compatibility constructor retained for focused unit tests of the legacy public behavior. */
    ProactiveTriggerEngine(
            CharacterStateService characterStateService,
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
            UserSimilarityService similarityService,
            UserInterestProfile interestProfile,
            UserService userService) {
        ProactiveAudienceService audience = new ProactiveAudienceService(
                characterStateService, characterActivityProvider);
        ProactiveTextGenerator generator = textGenerator::generate;
        this.emotionalService = new EmotionalProactiveService(
                characterStateService, notificationService, dispatcher, experienceService,
                activityProvider == null ? new NoopUserActivityProvider() : activityProvider, generator);
        this.contentService = new ContentProactiveService(
                postService, searchService, counterService, dispatcher, curationReader, redis,
                activityProvider == null ? new NoopUserActivityProvider() : activityProvider,
                characterActivityProvider, audience, generator);
        this.socialService = new SocialProactiveService(
                similarityService, interestProfile, userService, postService, dispatcher, redis,
                audience, generator);
    }

    @Scheduled(fixedDelay = 7_200_000L, initialDelay = 300_000L)
    public void checkTriggers() {
        emotionalService.checkTriggers();
        contentService.checkUnreadPosts();
    }

    @Scheduled(cron = "0 0 20 * * *")
    public void sendDailyHotDigest() {
        contentService.sendDailyHotDigest();
    }

    @Scheduled(cron = "0 30 10 * * 1")
    public void pushWeeklyCuration() {
        contentService.pushWeeklyCuration();
    }

    @Scheduled(cron = "0 */30 * * * *")
    public void detectRisingStars() {
        contentService.detectRisingStars();
    }

    @Scheduled(cron = "0 0 10,18 * * *")
    public void detectInterestMatches() {
        socialService.detectInterestMatches();
    }

    @Scheduled(cron = "0 0 12 * * *")
    public void introduceNewResidents() {
        socialService.introduceNewResidents();
    }

    /** Supplies indexed user activity data for proactive checks. */
    public interface UserActivityProvider {
        List<AbsentUser> findAbsentUsers(Duration threshold);

        List<UnreadPostDigest> findUnreadPostDigests(int minNewPosts);
    }

    public record AbsentUser(Long userId, String nickname, Instant lastInteraction) { }

    public record UnreadPostDigest(Long userId, String tagName, int postCount) { }

    @FunctionalInterface
    interface TextGenerator {
        String generate(String prompt);
    }

    static final class NoopUserActivityProvider implements UserActivityProvider {
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
