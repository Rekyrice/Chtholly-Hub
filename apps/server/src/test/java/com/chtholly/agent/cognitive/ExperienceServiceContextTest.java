package com.chtholly.agent.cognitive;

import com.chtholly.agent.experience.ArchivedExperienceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExperienceServiceContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ArchivedExperienceMapper.class, () -> mock(ArchivedExperienceMapper.class))
            .withUserConfiguration(ExperienceService.class);

    @Test
    void registersServiceWithProductionConstructor() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(ExperienceService.class));
    }
}
