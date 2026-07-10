package com.chtholly.agent.proactive;

import com.chtholly.agent.cognitive.ExperienceService;
import com.chtholly.agent.notification.Notification;
import com.chtholly.agent.notification.NotificationChannel;
import com.chtholly.agent.notification.NotificationService;
import com.chtholly.agent.proactive.ProactiveNotificationDispatcher.Category;
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
import com.chtholly.seed.CuratedPost;
import com.chtholly.seed.SeedCuration;
import com.chtholly.user.domain.User;
import com.chtholly.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProactiveTriggerEngineTest {

    private CharacterStateService characterStateService;
    private PostService postService;
    private NotificationService notificationService;
    private ProactiveNotificationDispatcher dispatcher;
    private ExperienceService experienceService;
    private SearchService searchService;
    private CounterService counterService;
    private SeedCurationReader curationReader;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private UserSimilarityService userSimilarityService;
    private UserInterestProfile userInterestProfile;
    private UserService userService;

    @BeforeEach
    void setUp() {
        characterStateService = mock(CharacterStateService.class);
        postService = mock(PostService.class);
        notificationService = mock(NotificationService.class);
        dispatcher = mock(ProactiveNotificationDispatcher.class);
        experienceService = mock(ExperienceService.class);
        searchService = mock(SearchService.class);
        counterService = mock(CounterService.class);
        curationReader = mock(SeedCurationReader.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        userSimilarityService = mock(UserSimilarityService.class);
        userInterestProfile = mock(UserInterestProfile.class);
        userService = mock(UserService.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(dispatcher.totalSentToday(anyLong())).thenReturn(0);
        when(dispatcher.send(anyLong(), any(Notification.class), any(Category.class))).thenReturn(true);
    }

    @Test
    void checkTriggersSendsMissingYouForAbsentUsers() {
        ProactiveTriggerEngine.UserActivityProvider activityProvider = new StubActivityProvider(
                List.of(new ProactiveTriggerEngine.AbsentUser(
                        7L,
                        "Reky",
                        Instant.now().minus(Duration.ofDays(4)))),
                List.of());
        ProactiveTriggerEngine engine = engine(activityProvider, null);
        when(experienceService.getRecentExperiences(3)).thenReturn(List.of());

        engine.checkTriggers();

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).send(eq(7L), notificationCaptor.capture(), eq(Category.GREET));
        Notification notification = notificationCaptor.getValue();
        assertThat(notification.type()).isEqualTo("missing-you");
        assertThat(notification.message()).isEqualTo("I kept your seat by the window.");
        assertThat(notification.channel()).isEqualTo(NotificationChannel.FLOATING);
    }

    @Test
    void checkTriggersSendsReturnBriefingForLongAbsentUsers() {
        CharacterStateUserActivityProvider characterProvider = mock(CharacterStateUserActivityProvider.class);
        when(characterProvider.findReturnBriefingCandidates()).thenReturn(List.of(
                new ProactiveTriggerEngine.AbsentUser(
                        8L,
                        "Yukino",
                        Instant.now().minus(Duration.ofDays(10)))));
        ProactiveTriggerEngine engine = new ProactiveTriggerEngine(
                characterStateService,
                postService,
                notificationService,
                dispatcher,
                experienceService,
                searchService,
                counterService,
                curationReader,
                redis,
                new StubActivityProvider(List.of(), List.of()),
                characterProvider,
                prompt -> prompt.contains("回归简报")
                        ? "你不在的这几天，仓库来了 3 篇新文章。热门话题：Rust 笔记"
                        : "I kept your seat by the window.",
                userSimilarityService,
                userInterestProfile,
                userService);
        when(experienceService.getRecentExperiences(3)).thenReturn(List.of());
        when(postService.countSince(ProactiveTriggerEngine.RETURN_BRIEFING_THRESHOLD)).thenReturn(3L);
        when(searchService.recommendHot(Set.of(), 3, 8L)).thenReturn(List.of(
                feedItem("101", "Rust 笔记")));

        engine.checkTriggers();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).send(eq(8L), captor.capture(), eq(Category.GREET));
        assertThat(captor.getValue().type()).isEqualTo("return-briefing");
        assertThat(captor.getValue().message()).contains("Rust 笔记");
    }

    @Test
    void sendDailyHotDigestNotifiesActiveUsers() {
        CharacterStateUserActivityProvider characterProvider = mock(CharacterStateUserActivityProvider.class);
        when(characterProvider.findActiveUserIds()).thenReturn(List.of(5L));
        when(searchService.recommendHot(Set.of(), 3, null)).thenReturn(List.of(
                feedItem("1", "芙莉莲观后感"),
                feedItem("2", "Java 排查记录")));
        ProactiveTriggerEngine engine = engine(new StubActivityProvider(List.of(), List.of()), characterProvider);

        engine.sendDailyHotDigest();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).send(eq(5L), captor.capture(), eq(Category.GREET));
        assertThat(captor.getValue().type()).isEqualTo("hot-digest");
        assertThat(captor.getValue().message()).contains("芙莉莲观后感");
    }

    @Test
    void pushWeeklyCurationUsesLatestCollectionNote() {
        CharacterStateUserActivityProvider characterProvider = mock(CharacterStateUserActivityProvider.class);
        when(characterProvider.findActiveUserIds()).thenReturn(List.of(6L));
        when(curationReader.getLatest()).thenReturn(new SeedCuration(
                List.of(new CuratedPost(1L, "标题", "评语")),
                "这周也有几篇值得慢慢读的文章。",
                Instant.parse("2026-07-07T10:00:00Z")));
        when(valueOps.get("agent:curation:last-push")).thenReturn(null);
        ProactiveTriggerEngine engine = engine(new StubActivityProvider(List.of(), List.of()), characterProvider);

        engine.pushWeeklyCuration();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).send(eq(6L), captor.capture(), eq(Category.RECOMMEND));
        assertThat(captor.getValue().type()).isEqualTo("weekly-curation");
        assertThat(captor.getValue().message()).contains("这周也有几篇值得慢慢读的文章");
    }

    @Test
    void detectRisingStarsMarksAndPushesPopularPost() {
        CharacterStateUserActivityProvider characterProvider = mock(CharacterStateUserActivityProvider.class);
        when(characterProvider.findActiveUserIds()).thenReturn(List.of(4L));
        PostSummary rising = new PostSummary(88L, "新星文章", "desc", Instant.now());
        List<PostSummary> recent = new java.util.ArrayList<>(List.of(rising));
        for (int i = 0; i < 4; i++) {
            recent.add(new PostSummary(90L + i, "普通文章" + i, "desc", Instant.now()));
        }
        when(postService.getRecentPosts(Duration.ofHours(24))).thenReturn(recent);
        when(counterService.getCounts(eq("post"), eq("88"), eq(List.of("like"))))
                .thenReturn(Map.of("like", 10L));
        when(counterService.getCounts(eq("post"), org.mockito.ArgumentMatchers.argThat(id -> !"88".equals(id)),
                eq(List.of("like"))))
                .thenReturn(Map.of("like", 1L));
        when(redis.hasKey("agent:rising-star:88")).thenReturn(false);
        ProactiveTriggerEngine engine = engine(new StubActivityProvider(List.of(), List.of()), characterProvider);

        engine.detectRisingStars();

        verify(valueOps).set(eq("agent:rising-star:88"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).send(eq(4L), captor.capture(), eq(Category.OBSERVATION));
        assertThat(captor.getValue().type()).isEqualTo("rising-star");
    }

    @Test
    void totalSentTodayBlocksFurtherPush() {
        when(dispatcher.totalSentToday(7L)).thenReturn(3);
        ProactiveTriggerEngine.UserActivityProvider activityProvider = new StubActivityProvider(
                List.of(new ProactiveTriggerEngine.AbsentUser(
                        7L,
                        "Reky",
                        Instant.now().minus(Duration.ofDays(4)))),
                List.of());
        ProactiveTriggerEngine engine = engine(activityProvider, null);
        when(experienceService.getRecentExperiences(3)).thenReturn(List.of());

        engine.checkTriggers();

        verify(dispatcher, never()).send(eq(7L), any(), any());
    }

    @Test
    void detectInterestMatchesNotifiesBothSidesAndDedups() {
        // Sakura / Chinatsu 风格：手动设高相似度时应搭桥
        CharacterStateUserActivityProvider characterProvider = mock(CharacterStateUserActivityProvider.class);
        when(characterProvider.findActiveUserIds()).thenReturn(List.of(101L, 102L));
        when(userSimilarityService.listProfileUserIds()).thenReturn(List.of(101L, 102L));
        when(userSimilarityService.findSimilarUsers(101L, ProactiveTriggerEngine.INTEREST_MATCH_TOP_K))
                .thenReturn(List.of(new SimilarUser(102L, 0.85)));
        when(userSimilarityService.findSimilarUsers(102L, ProactiveTriggerEngine.INTEREST_MATCH_TOP_K))
                .thenReturn(List.of(new SimilarUser(101L, 0.85)));
        when(userSimilarityService.commonInterestTags(eq(101L), eq(102L), anyDouble()))
                .thenReturn(List.of("治愈", "读书"));
        when(redis.hasKey("agent:interest-match:101:102")).thenReturn(false);
        when(userService.findById(101L)).thenReturn(Optional.of(user(101L, "Sakura")));
        when(userService.findById(102L)).thenReturn(Optional.of(user(102L, "Yukino")));

        ProactiveTriggerEngine engine = socialEngine(characterProvider, prompt -> {
            if (prompt.contains("搭桥")) {
                return "好像也有人喜欢治愈和读书呢。";
            }
            return "fallback";
        });

        engine.detectInterestMatches();

        ArgumentCaptor<Long> userCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher, times(2)).send(userCaptor.capture(), notificationCaptor.capture(), eq(Category.RECOMMEND));
        assertThat(userCaptor.getAllValues()).containsExactlyInAnyOrder(101L, 102L);
        assertThat(notificationCaptor.getAllValues())
                .allMatch(n -> "interest-match".equals(n.type()))
                .allMatch(n -> n.channel() == NotificationChannel.FLOATING);
        verify(valueOps).set(eq("agent:interest-match:101:102"), any(), eq(Duration.ofDays(30)));
    }

    @Test
    void detectInterestMatchesSkipsLowSimilarityLikeSakuraAndChinatsu() {
        CharacterStateUserActivityProvider characterProvider = mock(CharacterStateUserActivityProvider.class);
        when(characterProvider.findActiveUserIds()).thenReturn(List.of(201L, 202L));
        when(userSimilarityService.listProfileUserIds()).thenReturn(List.of(201L, 202L));
        // 治愈/生活 vs 热门/趣事 → 低相似度
        when(userSimilarityService.findSimilarUsers(201L, ProactiveTriggerEngine.INTEREST_MATCH_TOP_K))
                .thenReturn(List.of(new SimilarUser(202L, 0.12)));
        when(userSimilarityService.findSimilarUsers(202L, ProactiveTriggerEngine.INTEREST_MATCH_TOP_K))
                .thenReturn(List.of(new SimilarUser(201L, 0.12)));

        ProactiveTriggerEngine engine = socialEngine(characterProvider, prompt -> "unused");
        engine.detectInterestMatches();

        verify(dispatcher, never()).send(anyLong(), any(), any());
        verify(valueOps, never()).set(org.mockito.ArgumentMatchers.startsWith("agent:interest-match:"), any(), any());
    }

    @Test
    void introduceNewResidentsNotifiesActiveUsersAndMarksRedis() {
        CharacterStateUserActivityProvider characterProvider = mock(CharacterStateUserActivityProvider.class);
        when(characterProvider.findActiveUserIds()).thenReturn(List.of(10L, 11L));
        when(postService.listFirstTimePublisherIds(ProactiveTriggerEngine.NEW_RESIDENT_WINDOW))
                .thenReturn(List.of(99L));
        when(redis.hasKey("agent:new-resident-intro:99")).thenReturn(false);
        when(userService.findById(99L)).thenReturn(Optional.of(user(99L, "Kazahana")));
        when(userInterestProfile.buildProfile(99L)).thenReturn(new InterestProfile(
                99L,
                Map.of("番剧", 0.7, "日常", 0.3),
                List.of(),
                Instant.now()));

        ProactiveTriggerEngine engine = socialEngine(characterProvider, prompt -> {
            if (prompt.contains("新居民")) {
                return "仓库来了新朋友 Kazahana，好像很喜欢番剧呢。";
            }
            return "fallback";
        });

        engine.introduceNewResidents();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).send(eq(10L), captor.capture(), eq(Category.GREET));
        verify(dispatcher).send(eq(11L), any(Notification.class), eq(Category.GREET));
        assertThat(captor.getValue().type()).isEqualTo("new-resident");
        assertThat(captor.getValue().message()).contains("Kazahana");
        verify(dispatcher, never()).send(eq(99L), any(), any());
        verify(valueOps).set(eq("agent:new-resident-intro:99"), any(), eq(Duration.ofDays(7)));
    }

    @Test
    void introduceNewResidentsFallsBackToTemplateWhenLlmBlank() {
        CharacterStateUserActivityProvider characterProvider = mock(CharacterStateUserActivityProvider.class);
        when(characterProvider.findActiveUserIds()).thenReturn(List.of(10L));
        when(postService.listFirstTimePublisherIds(ProactiveTriggerEngine.NEW_RESIDENT_WINDOW))
                .thenReturn(List.of(88L));
        when(redis.hasKey("agent:new-resident-intro:88")).thenReturn(false);
        when(userService.findById(88L)).thenReturn(Optional.of(user(88L, "Chinatsu")));
        when(userInterestProfile.buildProfile(88L)).thenReturn(new InterestProfile(
                88L, Map.of("趣事", 1.0), List.of(), Instant.now()));

        ProactiveTriggerEngine engine = socialEngine(characterProvider, prompt -> "  ");
        engine.introduceNewResidents();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).send(eq(10L), captor.capture(), eq(Category.GREET));
        assertThat(captor.getValue().message()).contains("Chinatsu").contains("趣事");
    }

    private ProactiveTriggerEngine engine(
            ProactiveTriggerEngine.UserActivityProvider activityProvider,
            CharacterStateUserActivityProvider characterProvider) {
        return new ProactiveTriggerEngine(
                characterStateService,
                postService,
                notificationService,
                dispatcher,
                experienceService,
                searchService,
                counterService,
                curationReader,
                redis,
                activityProvider,
                characterProvider,
                prompt -> "I kept your seat by the window.",
                userSimilarityService,
                userInterestProfile,
                userService);
    }

    private ProactiveTriggerEngine socialEngine(
            CharacterStateUserActivityProvider characterProvider,
            ProactiveTriggerEngine.TextGenerator textGenerator) {
        return new ProactiveTriggerEngine(
                characterStateService,
                postService,
                notificationService,
                dispatcher,
                experienceService,
                searchService,
                counterService,
                curationReader,
                redis,
                new StubActivityProvider(List.of(), List.of()),
                characterProvider,
                textGenerator,
                userSimilarityService,
                userInterestProfile,
                userService);
    }

    private static User user(long id, String nickname) {
        return User.builder().id(id).nickname(nickname).build();
    }

    private static FeedItemResponse feedItem(String id, String title) {
        return new FeedItemResponse(
                id, "slug-" + id, title, "desc", null,
                List.of("tag"), null, "author", null,
                1L, 0L, false, false, false);
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
