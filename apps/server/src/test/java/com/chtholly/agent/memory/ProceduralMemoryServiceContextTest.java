package com.chtholly.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProceduralMemoryServiceContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(ProceduralMemoryService.class);

    @Test
    void registersServiceWithProductionConstructor() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(ProceduralMemoryService.class));
    }
}
