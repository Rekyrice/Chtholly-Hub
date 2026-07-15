package com.chtholly.agent.context.contributor;

import com.chtholly.agent.state.EmotionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RelationshipContextContributorTest {

    @ParameterizedTest
    @CsvSource({
            "0,深夜",
            "2,凌晨",
            "6,早晨",
            "10,上午",
            "15,下午",
            "19,傍晚",
            "22,深夜"
    })
    void timePeriodLabelMapsHourToChinesePeriod(int hour, String expectedLabel) {
        assertThat(RelationshipContextContributor.timePeriodLabel(hour)).isEqualTo(expectedLabel);
    }

    @ParameterizedTest
    @CsvSource({
            "0.0,陌生人",
            "0.1,刚认识",
            "0.3,熟悉的人",
            "0.6,朋友",
            "0.9,很亲近的人"
    })
    void intimacyLabelMapsScoreToRelationshipLabel(double intimacy, String expectedLabel) {
        assertThat(RelationshipContextContributor.intimacyLabel(intimacy)).isEqualTo(expectedLabel);
    }

    @ParameterizedTest
    @CsvSource({
            "0.0,初识",
            "0.1,刚开始熟悉",
            "0.3,熟悉",
            "0.7,亲近"
    })
    void formatIntimacyMapsScoreToPromptLabel(double intimacy, String expectedLabel) {
        assertThat(RelationshipContextContributor.formatIntimacy(intimacy)).isEqualTo(expectedLabel);
    }

    @ParameterizedTest
    @CsvSource({
            "0.4,轻快",
            "0.0,平静",
            "-0.2,有点低落",
            "-0.6,低落"
    })
    void formatMoodMapsValenceToStateLabel(double valence, String expectedLabel) {
        assertThat(RelationshipContextContributor.formatMood(valence)).isEqualTo(expectedLabel);
    }

    @ParameterizedTest
    @CsvSource({
            "0.6,心情很好，有点开心",
            "0.3,平静偏暖，感觉不错",
            "0.0,平静，安安静静的",
            "-0.3,有点低落，说不上为什么",
            "-0.6,心情不太好，想安静一会儿"
    })
    void describeMoodMapsValenceToNarrative(double valence, String expectedNarrative) {
        assertThat(RelationshipContextContributor.describeMood(valence)).isEqualTo(expectedNarrative);
    }

    @Test
    void describeEmotionMapsKnownLabelsAndFallsBackForUnknownLabel() {
        assertThat(describeEmotion("好奇")).isEqualTo("对当前话题感到好奇，想了解更多");
        assertThat(describeEmotion("开心")).isEqualTo("因为刚才的互动感到开心");
        assertThat(describeEmotion("感伤")).isEqualTo("有些感伤，但愿意继续聊");
        assertThat(describeEmotion("认真")).isEqualTo("在认真思考");
        assertThat(describeEmotion("困")).isEqualTo("有点犯困了");
        assertThat(describeEmotion("未知")).isEqualTo("平静地在这里");
        assertThat(RelationshipContextContributor.describeEmotion(null)).isEqualTo("平静地在这里");
    }

    private String describeEmotion(String label) {
        return RelationshipContextContributor.describeEmotion(
                new EmotionState(label, 0.5, Instant.EPOCH, "test"));
    }
}
