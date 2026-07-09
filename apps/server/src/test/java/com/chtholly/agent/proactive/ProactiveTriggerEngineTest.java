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
import com.chtholly.search.service.SearchService;
import com.chtholly.seed.CuratedPost;
import com.chtholly.seed.SeedCuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                        : "I kept your seat by the window.");
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
                prompt -> "I kept your seat by the window.");
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
