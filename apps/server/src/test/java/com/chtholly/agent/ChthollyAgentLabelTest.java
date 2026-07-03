package com.chtholly.agent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ChthollyAgentLabelTest {

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
        assertThat(ChthollyAgent.timePeriodLabel(hour)).isEqualTo(expectedLabel);
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
        assertThat(ChthollyAgent.intimacyLabel(intimacy)).isEqualTo(expectedLabel);
    }
}
