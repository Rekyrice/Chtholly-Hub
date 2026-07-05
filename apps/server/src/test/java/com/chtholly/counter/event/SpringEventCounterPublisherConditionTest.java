package com.chtholly.counter.event;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SpringEventCounterPublisherConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SpringEventCounterPublisher.class);

    @Test
    void registersSpringPublisherWhenKafkaIsDisabled() {
        contextRunner
                .withPropertyValues("kafka.enabled=false")
                .run(context -> assertThat(context).hasSingleBean(CounterEventPublisher.class));
    }
}
