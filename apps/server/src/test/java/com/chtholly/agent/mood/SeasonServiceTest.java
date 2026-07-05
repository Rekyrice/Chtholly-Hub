package com.chtholly.agent.mood;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SeasonServiceTest {

    @ParameterizedTest
    @CsvSource({
            "2026-03-01T00:00:00Z, SPRING",
            "2026-05-31T00:00:00Z, SPRING",
            "2026-06-01T00:00:00Z, SUMMER",
            "2026-08-31T00:00:00Z, SUMMER",
            "2026-09-01T00:00:00Z, AUTUMN",
            "2026-11-30T00:00:00Z, AUTUMN",
            "2026-12-01T00:00:00Z, WINTER",
            "2026-02-28T00:00:00Z, WINTER"
    })
    void mapsNorthernHemisphereMonthsToSeasons(String now, SeasonService.Season expected) {
        SeasonService service = serviceAt(now);

        assertThat(service.getCurrentSeason()).isEqualTo(expected);
    }

    @Test
    void returnsSeasonalPromptForCurrentSeason() {
        assertThat(serviceAt("2026-04-10T00:00:00Z").getSeasonalPrompt())
                .isEqualTo("现在是春天呢。适合推荐一些治愈系、新开始的故事。");
        assertThat(serviceAt("2026-07-10T00:00:00Z").getSeasonalPrompt())
                .isEqualTo("夏天到了。可以推荐一些活力、冒险、青春的作品。");
        assertThat(serviceAt("2026-10-10T00:00:00Z").getSeasonalPrompt())
                .isEqualTo("秋天了……适合感性一点的故事，关于离别和怀念的。");
        assertThat(serviceAt("2026-01-10T00:00:00Z").getSeasonalPrompt())
                .isEqualTo("冬天好安静。推荐一些温暖的、关于陪伴的故事吧。");
    }

    @Test
    void returnsSeasonalThoughtForCurrentSeason() {
        assertThat(serviceAt("2026-04-10T00:00:00Z").getSeasonalThought())
                .isEqualTo("樱花开了呢……虽然很快就会落下，但盛开的时候真的很美。");
        assertThat(serviceAt("2026-07-10T00:00:00Z").getSeasonalThought())
                .isEqualTo("好热……不过夏天的夕阳总是特别好看。");
        assertThat(serviceAt("2026-10-10T00:00:00Z").getSeasonalThought())
                .isEqualTo("落叶的季节。每一片叶子都像是一个故事呢。");
        assertThat(serviceAt("2026-01-10T00:00:00Z").getSeasonalThought())
                .isEqualTo("好安静……下雪的时候，世界好像被按下了静音键。");
    }

    private static SeasonService serviceAt(String instant) {
        return new SeasonService(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
}
