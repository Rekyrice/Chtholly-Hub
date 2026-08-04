package com.chtholly.agent.ws;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTurnCoordinatorContextTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("llm.enabled=true")
            .withBean(StringRedisTemplate.class, () -> redis)
            .withUserConfiguration(AgentTurnCoordinator.class);

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void springContextUsesRedisBackedCoordinator() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("ACQUIRED|turn-context");

        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentTurnCoordinator.class);

            AgentTurnCoordinator.AcquireResult result = context
                    .getBean(AgentTurnCoordinator.class)
                    .acquire(
                            1L,
                            "sess-context",
                            "request-context",
                            "turn-context",
                            Duration.ofSeconds(30));

            assertThat(result.status()).isEqualTo(AgentTurnCoordinator.AcquireStatus.ACQUIRED);
            verify(redis).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
        });
    }
}
