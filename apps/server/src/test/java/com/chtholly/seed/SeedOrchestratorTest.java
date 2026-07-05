package com.chtholly.seed;

import com.chtholly.post.id.SnowflakeIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeedOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-07-05T00:00:00Z");

    @Mock
    private SeedMapper mapper;
    @Mock
    private BangumiRecommendationSource bangumiSource;
    @Mock
    private SeedTextGenerator textGenerator;

    private SeedOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        lenient().when(textGenerator.bangumiReview(any())).thenAnswer(inv -> "我读完简介以后，觉得这部作品适合安静的晚上看。");
        lenient().when(textGenerator.postBody(any(), any())).thenAnswer(inv -> "# " + inv.getArgument(1, SeedPostPlan.class).title()
                + "\n\n这是根据账号人设生成的种子文章，用来让仓库刚上线时不那么空。");
        lenient().when(textGenerator.comment(any(), any(), any())).thenReturn("这篇读完挺有共鸣的，尤其是中间那段经验。");

        orchestrator = new SeedOrchestrator(
                mapper,
                bangumiSource,
                textGenerator,
                new SnowflakeIdGenerator(1, 2),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void given_fullDryRun_when_run_then_buildsAccountsPostsRecommendationsAndInteractionsWithoutWriting() {
        when(bangumiSource.fetchTopAnime(20)).thenReturn(List.of(
                subject(1L, "葬送的芙莉莲", 8.9),
                subject(2L, "评分不够的作品", 7.0)));

        SeedRunSummary summary = orchestrator.run(new SeedRunOptions(SeedRunMode.FULL, true));

        assertThat(summary.accounts()).isEqualTo(8);
        assertThat(summary.posts()).isBetween(24, 40);
        assertThat(summary.comments()).isGreaterThanOrEqualTo(16);
        assertThat(summary.follows()).isGreaterThanOrEqualTo(24);
        assertThat(summary.recommendations()).isEqualTo(1);
        assertThat(summary.dryRun()).isTrue();
        verify(mapper, never()).insertSeedUser(any());
        verify(mapper, never()).markSeed(any(), any());
    }

    @Test
    void given_existingFullSeed_when_run_then_skipsWritesAndReportsSkipped() {
        when(mapper.existsSeed("full")).thenReturn(true);

        SeedRunSummary summary = orchestrator.run(new SeedRunOptions(SeedRunMode.FULL, false));

        assertThat(summary.skipped()).isTrue();
        assertThat(summary.accounts()).isZero();
        verify(mapper, never()).insertSeedUser(any());
        verify(mapper, never()).insertBangumiRecommendation(any());
    }

    @Test
    void given_bangumiMode_when_run_then_filtersLowScoreAndPersistsRecommendations() {
        when(mapper.existsSeed("bangumi")).thenReturn(false);
        when(bangumiSource.fetchTopAnime(20)).thenReturn(List.of(
                subject(1L, "葬送的芙莉莲", 8.9),
                subject(2L, "虫师", 8.6),
                subject(3L, "低分动画", 6.9)));

        SeedRunSummary summary = orchestrator.run(new SeedRunOptions(SeedRunMode.BANGUMI, false));

        assertThat(summary.recommendations()).isEqualTo(2);
        assertThat(summary.accounts()).isZero();
        verify(mapper, times(2)).insertBangumiRecommendation(any());
        verify(mapper).markSeed(eq("bangumi"), any());
    }

    @Test
    void given_accountsMode_when_run_then_createsSeedAccountsContentAndSocialGraph() {
        when(mapper.existsSeed("accounts")).thenReturn(false);

        SeedRunSummary summary = orchestrator.run(new SeedRunOptions(SeedRunMode.ACCOUNTS, false));

        assertThat(summary.accounts()).isEqualTo(8);
        assertThat(summary.posts()).isBetween(24, 40);
        assertThat(summary.comments()).isGreaterThanOrEqualTo(16);
        assertThat(summary.follows()).isGreaterThanOrEqualTo(24);
        assertThat(summary.recommendations()).isZero();
        verify(mapper, times(8)).insertSeedUser(any());
        verify(mapper, times(24)).insertSeedPost(any());
        verify(mapper, times(16)).insertSeedComment(any());
        verify(mapper, times(24)).upsertFollowing(any());
        verify(mapper, times(24)).upsertFollower(any());
        verify(mapper).markSeed(eq("accounts"), any());
    }

    private static BangumiSubjectSeed subject(long id, String title, double score) {
        return new BangumiSubjectSeed(
                id,
                title,
                title,
                "https://example.com/" + id + ".jpg",
                score,
                "这是一部关于时间、记忆和日常的作品。",
                List.of("动画", "治愈"));
    }
}
