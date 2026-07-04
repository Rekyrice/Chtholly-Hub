package com.chtholly.agent.api;

import com.chtholly.agent.cognitive.ExperienceService;
import com.chtholly.agent.cognitive.Observation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentExperienceControllerTest {

    @Test
    void recentReturnsExperienceResponsesNewestFirst() {
        ExperienceService experienceService = mock(ExperienceService.class);
        Instant now = Instant.parse("2026-07-04T06:00:00Z");
        when(experienceService.getRecentExperiences(3)).thenReturn(List.of(
                new Observation("嗯，这篇文章有一点安静的光。", 0.86, now, "cognitive-cycle")
        ));
        AgentExperienceController controller = new AgentExperienceController(experienceService);

        var responses = controller.recent(3);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().text()).isEqualTo("嗯，这篇文章有一点安静的光。");
        assertThat(responses.getFirst().valueScore()).isEqualTo(0.86);
        verify(experienceService).getRecentExperiences(3);
    }

    @Test
    void recentClampsLimitToPublicBounds() {
        ExperienceService experienceService = mock(ExperienceService.class);
        AgentExperienceController controller = new AgentExperienceController(experienceService);

        controller.recent(200);
        controller.recent(0);

        verify(experienceService).getRecentExperiences(20);
        verify(experienceService).getRecentExperiences(1);
    }
}
