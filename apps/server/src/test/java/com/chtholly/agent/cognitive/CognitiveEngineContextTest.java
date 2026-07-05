package com.chtholly.agent.cognitive;

import com.chtholly.agent.learning.InsightService;
import com.chtholly.post.service.PostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CognitiveEngineContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(PostService.class, () -> mock(PostService.class))
            .withBean(InsightService.class, () -> mock(InsightService.class))
            .withBean(ExperienceService.class, () -> mock(ExperienceService.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(CognitiveEngine.class);

    @Test
    void registersEngineWithProductionConstructorAndOptionalProviders() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(CognitiveEngine.class));
    }
}
