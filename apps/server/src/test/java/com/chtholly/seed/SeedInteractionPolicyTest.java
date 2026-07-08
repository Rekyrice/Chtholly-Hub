package com.chtholly.seed;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeedInteractionPolicyTest {

    @Test
    void given_matchingInterestsAndProbabilityHit_when_shouldComment_then_returnsTrue() {
        SeedInteractionPolicy policy = new SeedInteractionPolicy(() -> 0.12);
        SeedAccountProfile account = account("yukino", List.of("技术文章", "编程", "动漫深度解析"));

        boolean result = policy.shouldComment(
                account,
                List.of("技术", "Java", "编程"),
                "这是一篇关于 Java 线程池排查的文章",
                0.30,
                0.60);

        assertThat(result).isTrue();
    }

    @Test
    void given_matchingInterestsButProbabilityMiss_when_shouldComment_then_returnsFalse() {
        SeedInteractionPolicy policy = new SeedInteractionPolicy(() -> 0.91);
        SeedAccountProfile account = account("sakura", List.of("治愈系", "生活感悟", "读书笔记"));

        boolean result = policy.shouldComment(
                account,
                List.of("生活", "治愈", "日常"),
                "午休时看云，也算一种恢复吧",
                0.30,
                0.50);

        assertThat(result).isFalse();
    }

    @Test
    void given_lowInterestScore_when_shouldComment_then_returnsFalseEvenIfProbabilityHit() {
        SeedInteractionPolicy policy = new SeedInteractionPolicy(() -> 0.01);
        SeedAccountProfile account = account("kazahana", List.of("书籍推荐", "文学评论", "历史"));

        boolean result = policy.shouldComment(
                account,
                List.of("Godot", "游戏开发", "手感"),
                "给小游戏做手感，比想象中更难",
                0.30,
                0.60);

        assertThat(result).isFalse();
    }

    @Test
    void given_similarWords_when_interestScore_then_treatsPartialTagMatchesAsRelevant() {
        SeedInteractionPolicy policy = new SeedInteractionPolicy(() -> 0.0);
        SeedAccountProfile account = account("chinatsu", List.of("新番推荐", "热门话题", "生活趣事"));

        double score = policy.interestScore(
                account,
                List.of("番剧", "推荐"),
                "本季追番清单：安静一点也没关系");

        assertThat(score).isGreaterThanOrEqualTo(0.60);
    }

    private static SeedAccountProfile account(String handle, List<String> tags) {
        return new SeedAccountProfile(
                handle,
                handle,
                "bio",
                "/avatar.png",
                "SECRET",
                LocalDate.of(2000, 1, 1),
                "school",
                tags,
                "persona");
    }
}
