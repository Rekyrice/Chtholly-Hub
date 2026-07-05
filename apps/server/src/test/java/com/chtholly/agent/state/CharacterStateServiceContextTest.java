package com.chtholly.agent.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CharacterStateServiceContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(CharacterStateService.class);

    @Test
    void registersServiceWithProductionConstructor() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(CharacterStateService.class));
    }
}
