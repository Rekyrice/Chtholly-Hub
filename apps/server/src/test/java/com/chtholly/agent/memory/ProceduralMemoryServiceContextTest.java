package com.chtholly.agent.memory;

import com.chtholly.agent.learning.InsightService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProceduralMemoryServiceContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(InsightService.class, () -> mock(InsightService.class))
            .withUserConfiguration(ProceduralMemoryService.class);

    @Test
    void registersServiceWithProductionConstructor() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(ProceduralMemoryService.class));
    }
}
