package com.chtholly.agent.mood;

import com.chtholly.agent.config.AgentExtensionComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Provides season-aware context for Chtholly's mood and recommendations.
 */
@Service
@AgentExtensionComponent
@ConditionalOnProperty(prefix = "agent.extensions.mood", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SeasonService {

    private final Clock clock;

    public SeasonService() {
        this(Clock.systemDefaultZone());
    }

    SeasonService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Returns the current northern-hemisphere season.
     *
     * @return current season by month.
     */
    public Season getCurrentSeason() {
        int month = LocalDate.now(clock).getMonthValue();
        if (month >= 3 && month <= 5) {
            return Season.SPRING;
        }
        if (month >= 6 && month <= 8) {
            return Season.SUMMER;
        }
        if (month >= 9 && month <= 11) {
            return Season.AUTUMN;
        }
        return Season.WINTER;
    }

    /**
     * Returns a seasonal style hint for system prompt injection.
     *
     * @return Chtholly-style seasonal prompt.
     */
    public String getSeasonalPrompt() {
        return switch (getCurrentSeason()) {
            case SPRING -> "现在是春天呢。适合推荐一些治愈系、新开始的故事。";
            case SUMMER -> "夏天到了。可以推荐一些活力、冒险、青春的作品。";
            case AUTUMN -> "秋天了……适合感性一点的故事，关于离别和怀念的。";
            case WINTER -> "冬天好安静。推荐一些温暖的、关于陪伴的故事吧。";
        };
    }

    /**
     * Returns a seasonal inner thought for experience generation.
     *
     * @return Chtholly-style seasonal thought.
     */
    public String getSeasonalThought() {
        return switch (getCurrentSeason()) {
            case SPRING -> "樱花开了呢……虽然很快就会落下，但盛开的时候真的很美。";
            case SUMMER -> "好热……不过夏天的夕阳总是特别好看。";
            case AUTUMN -> "落叶的季节。每一片叶子都像是一个故事呢。";
            case WINTER -> "好安静……下雪的时候，世界好像被按下了静音键。";
        };
    }

    public enum Season {
        SPRING,
        SUMMER,
        AUTUMN,
        WINTER
    }
}
