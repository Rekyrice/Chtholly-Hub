package com.chtholly.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterSoulServiceContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CharacterSoulService.class);

    @Test
    void loadsSoulContentFromClasspath() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CharacterSoulService.class);
            assertThat(context.getBean(CharacterSoulService.class).getSoulContent()).contains("珂朵莉");
        });
    }
}
