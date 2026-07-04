package com.chtholly.agent.cognitive;

import com.chtholly.agent.learning.InsightService;
import com.chtholly.post.api.dto.PostSummary;
import com.chtholly.post.service.PostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CognitiveEngineTest {

    private static final Instant NOW = Instant.parse("2026-07-04T01:00:00Z");

    private PostService postService;
    private InsightService insightService;
    private ExperienceService experienceService;

    @BeforeEach
    void setUp() {
        postService = mock(PostService.class);
        insightService = mock(InsightService.class);
        experienceService = mock(ExperienceService.class);
    }

    @Test
    void cognitiveCycleStoresOnlyValuableCharacterConsistentThoughts() {
        when(postService.getRecentPosts(Duration.ofHours(6))).thenReturn(List.of(
                new PostSummary(1L, "芙莉莲观后感", "关于时间的文章", Instant.parse("2026-07-04T00:30:00Z"))
        ));
        CognitiveEngine engine = engine(input -> List.of(
                new Observation("我觉得这篇文章里关于时间的部分，很适合慢慢读。", 0.92, NOW, "test"),
                new Observation("作为AI助手，我建议立刻推送热点。", 0.95, NOW, "test"),
                new Observation("这条想法太轻了。", 0.2, NOW, "test"),
                new Observation("我想把那句关于等待的话记下来。", 0.81, NOW, "test"),
                new Observation("嗯，这篇文字有一点安静的光。", 0.78, NOW, "test"),
                new Observation("我还想再生成第四条。", 0.77, NOW, "test")
        ));

        engine.cognitiveCycle();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Observation>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(experienceService).storeExperiences(captor.capture());
        assertThat(captor.getValue())
                .extracting(Observation::text)
                .containsExactly(
                        "我觉得这篇文章里关于时间的部分，很适合慢慢读。",
                        "我想把那句关于等待的话记下来。",
                        "嗯，这篇文字有一点安静的光。");
    }

    @Test
    void cognitiveCycleSkipsStorageWhenNoValuableThoughts() {
        when(postService.getRecentPosts(Duration.ofHours(6))).thenReturn(List.of());
        CognitiveEngine engine = engine(input -> List.of(
                new Observation("作为AI助手，我会高频运营。", 0.9, NOW, "test"),
                new Observation("太普通了。", 0.1, NOW, "test")
        ));

        engine.cognitiveCycle();

        verify(experienceService, never()).storeExperiences(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void triggerIfDueRunsAtMostOnceWithinThirtyMinutes() {
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        MutableClock clock = new MutableClock(now);
        CognitiveEngine engine = new CognitiveEngine(
                postService,
                insightService,
                experienceService,
                input -> List.of(),
                clock);
        when(postService.getRecentPosts(Duration.ofHours(6))).thenReturn(List.of());

        assertThat(engine.triggerIfDue()).isTrue();
        assertThat(engine.triggerIfDue()).isFalse();
        now.set(NOW.plus(Duration.ofMinutes(31)));
        assertThat(engine.triggerIfDue()).isTrue();
    }

    private CognitiveEngine engine(CognitiveEngine.ObservationGenerator generator) {
        return new CognitiveEngine(
                postService,
                insightService,
                experienceService,
                generator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(AtomicReference<Instant> now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
