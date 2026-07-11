package com.chtholly.agent.context.contributor;

import com.chtholly.agent.context.ContextContribution;
import com.chtholly.agent.context.ContextContributor;
import com.chtholly.agent.context.ContextOrder;
import com.chtholly.agent.context.ContextRequest;
import com.chtholly.agent.mood.SeasonService;
import com.chtholly.agent.state.CharacterState;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.agent.state.EmotionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Locale;

/** Renders relational state, current mood, emotion, and optional seasonal feeling. */
@Slf4j
@Component
public class RelationshipContextContributor implements ContextContributor {

    private final CharacterStateService stateService;
    private final SeasonService seasonService;

    @Autowired
    public RelationshipContextContributor(CharacterStateService stateService,
                                          ObjectProvider<SeasonService> seasonServiceProvider) {
        this(stateService, seasonServiceProvider.getIfAvailable());
    }

    public RelationshipContextContributor(CharacterStateService stateService, SeasonService seasonService) {
        this.stateService = stateService;
        this.seasonService = seasonService;
    }

    @Override
    public String name() {
        return "relationship";
    }

    @Override
    public int order() {
        return ContextOrder.RELATIONSHIP;
    }

    @Override
    public ContextContribution contribute(ContextRequest request) {
        StringBuilder prompt = new StringBuilder();
        appendCharacterState(prompt, request.anchors().relational());
        boolean degraded = appendSeasonalFeeling(prompt);
        return new ContextContribution(name(), order(), prompt.toString(), degraded);
    }

    private void appendCharacterState(StringBuilder prompt, CharacterState state) {
        CharacterState safeState = state == null ? CharacterState.defaultState() : state;
        double moodBaseline = stateService.getMoodBaseline();
        double moodValence = stateService.getMoodValence();
        EmotionState emotion = stateService.getCurrentEmotion();
        double intimacy = safeState.relationship().intimacy();

        prompt.append("## 当前状态\n\n")
                .append("你和这位用户的亲密度：").append(formatIntimacy(intimacy))
                .append("（").append(intimacyLabel(intimacy)).append("）\n")
                .append("你当前的心境：").append(formatMood(safeState.mood().valence())).append('\n')
                .append("当前时间段：").append(timePeriodLabel())
                .append("（心境基线：").append(moodBaseline).append("）\n")
                .append("- 心情：").append(describeMood(moodValence))
                .append("（valence: ").append(String.format(Locale.ROOT, "%.2f", moodValence)).append("）\n")
                .append("- 情绪：").append(describeEmotion(emotion))
                .append("（").append(emotion.label()).append(", intensity: ")
                .append(String.format(Locale.ROOT, "%.2f", emotion.intensity())).append("）\n")
                .append("互动次数：").append(safeState.relationship().interactionCount());
    }

    private boolean appendSeasonalFeeling(StringBuilder prompt) {
        if (seasonService == null) {
            return false;
        }
        try {
            String seasonalPrompt = seasonService.getSeasonalPrompt();
            if (!hasText(seasonalPrompt)) {
                return false;
            }
            prompt.append("\n\n## 季节感受\n\n").append(seasonalPrompt.trim());
            return false;
        } catch (RuntimeException e) {
            log.warn("Seasonal context failed", e);
            return true;
        }
    }

    private static String timePeriodLabel() {
        return timePeriodLabel(LocalTime.now().getHour());
    }

    static String timePeriodLabel(int hour) {
        if (hour >= 6 && hour < 9) return "早晨";
        if (hour >= 9 && hour < 12) return "上午";
        if (hour >= 12 && hour < 18) return "下午";
        if (hour >= 18 && hour < 21) return "傍晚";
        if (hour >= 21 || hour < 1) return "深夜";
        return "凌晨";
    }

    static String intimacyLabel(double intimacy) {
        if (intimacy < 0.1) return "陌生人";
        if (intimacy < 0.3) return "刚认识";
        if (intimacy < 0.6) return "熟悉的人";
        if (intimacy < 0.9) return "朋友";
        return "很亲近的人";
    }

    static String formatIntimacy(double intimacy) {
        if (intimacy >= 0.7) return "亲近";
        if (intimacy >= 0.3) return "熟悉";
        if (intimacy > 0.0) return "刚开始熟悉";
        return "初识";
    }

    static String formatMood(double valence) {
        if (valence >= 0.4) return "轻快";
        if (valence > -0.2) return "平静";
        if (valence > -0.6) return "有点低落";
        return "低落";
    }

    static String describeMood(double valence) {
        if (valence > 0.5) return "心情很好，有点开心";
        if (valence > 0.2) return "平静偏暖，感觉不错";
        if (valence > -0.2) return "平静，安安静静的";
        if (valence > -0.5) return "有点低落，说不上为什么";
        return "心情不太好，想安静一会儿";
    }

    static String describeEmotion(EmotionState emotion) {
        EmotionState safeEmotion = emotion == null
                ? new EmotionState("平静", 0.2, java.time.Instant.EPOCH, "default")
                : emotion;
        return switch (safeEmotion.label()) {
            case "好奇" -> "对当前话题感到好奇，想了解更多";
            case "开心" -> "因为刚才的互动感到开心";
            case "感伤" -> "有些感伤，但愿意继续聊";
            case "认真" -> "在认真思考";
            case "困" -> "有点犯困了";
            default -> "平静地在这里";
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
